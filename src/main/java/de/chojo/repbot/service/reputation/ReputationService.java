package de.chojo.repbot.service.reputation;

import de.chojo.jdautil.localization.ILocalizer;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.parsing.Verifier;
import de.chojo.repbot.analyzer.ContextResolver;
import de.chojo.repbot.analyzer.MessageContext;
import de.chojo.repbot.analyzer.results.match.ThankType;
import de.chojo.repbot.config.elements.MagicImage;
import de.chojo.repbot.dao.access.guild.settings.Settings;
import de.chojo.repbot.dao.access.guild.settings.sub.Reputation;
import de.chojo.repbot.dao.provider.Guilds;
import de.chojo.repbot.service.RoleAssigner;
import de.chojo.repbot.util.EmojiDebug;
import de.chojo.repbot.util.Messages;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.awt.Color;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.slf4j.LoggerFactory.getLogger;

public class ReputationService {
    private static final Logger log = getLogger(ReputationService.class);
    private final Guilds guilds;
    private final RoleAssigner assigner;
    private final MagicImage magicImage;
    private final ContextResolver contextResolver;
    private final ILocalizer localizer;
    private Instant lastEasterEggSent = Instant.EPOCH;

    public ReputationService(Guilds guilds, ContextResolver contextResolver, RoleAssigner assigner, MagicImage magicImage, ILocalizer localizer) {
        this.guilds = guilds;
        this.assigner = assigner;
        this.magicImage = magicImage;
        this.contextResolver = contextResolver;
        this.localizer = localizer;
    }

    /**
     * Submit a reputation.
     * <p>
     * This reputation will be checked by several factors based on the {@link de.chojo.repbot.dao.access.guild.settings.Settings}.
     *
     * @param guild      guild where the vote was given
     * @param donor      donor of the reputation
     * @param receiver   receiver of the reputation
     * @param message    triggered message
     * @param refMessage reference message if present
     * @param type       type of reputation source
     * @return true if the reputation was counted and is valid
     */
    public boolean submitReputation(Guild guild, Member donor, Member receiver, Message message, @Nullable Message refMessage, ThankType type) {
        var repGuild = guilds.guild(guild);
        log.trace("Submitting reputation for message {} of type {}", message.getIdLong(), type);
        // block bots
        if (receiver.getUser().isBot()) {
            log.trace("Author of {} is bot.", message.getIdLong());
            return false;
        }

        var settings = repGuild.settings();
        var messageSettings = settings.reputation();
        var thankSettings = settings.thanking();
        var analyzer = repGuild.reputation().analyzer();

        analyzer.log(message, SubmitResult.of(SubmitResultType.SUBMITTING,
                Replacement.create("type", "$%s$".formatted(type.nameLocaleKey())),
                Replacement.createMention(donor)));

        // block non reputation channel
        if (!thankSettings.channels().isEnabled(message.getGuildChannel())) {
            analyzer.log(message, SubmitResult.of(SubmitResultType.CHANNEL_INACTIVE));
            log.trace("Channel of message {} is not enabled", message.getIdLong());
            return false;
        }

        if (isTypeDisabled(type, messageSettings)) {
            analyzer.log(message, SubmitResult.of(SubmitResultType.THANK_TYPE_DISABLED, Replacement.create("thanktype", "$%s$".formatted(type.nameLocaleKey()))));
            log.trace("Thank type {} for message {} is disabled", type, message.getIdLong());
            return false;
        }

        var context = getContext(donor, message, type, settings);

        if (isSelfVote(donor, receiver, message)) {
            analyzer.log(message, SubmitResult.of(SubmitResultType.SELF_VOTE));
            log.trace("Detected self vote on {}", message.getIdLong());
            return false;
        }

        if (assertAbuseProtection(guild, donor, receiver, message, refMessage, context)) return false;

        return log(guild, donor, receiver, message, refMessage, type, settings);
    }

    private MessageContext getContext(Member donor, Message message, ThankType type, Settings settings) {
        MessageContext context;
        if (type == ThankType.REACTION) {
            // Check if user was recently seen in this channel.
            context = contextResolver.getCombinedContext(donor, message, settings);
        } else {
            context = contextResolver.getCombinedContext(message, settings);
        }
        return context;
    }

    private boolean assertAbuseProtection(Guild guild, Member donor, Member receiver, Message message, @Nullable Message refMessage, MessageContext context) {
        var repGuild = guilds.guild(guild);
        var analyzer = repGuild.reputation().analyzer();
        var settings = repGuild.settings();
        var addEmoji = settings.general().isEmojiDebug();
        var abuseSettings = settings.abuseProtection();

        // Abuse Protection: target context
        if (!context.members().contains(receiver) && abuseSettings.isReceiverContext()) {
            analyzer.log(message, SubmitResult.of(SubmitResultType.TARGET_NOT_IN_CONTEXT, Replacement.createMention(receiver)));
            log.trace("Receiver is not in context of {}", message.getIdLong());
            if (addEmoji) Messages.markMessage(message, EmojiDebug.TARGET_NOT_IN_CONTEXT);
            return true;
        }

        // Abuse Protection: donor context
        if (!context.members().contains(donor) && abuseSettings.isDonorContext()) {
            log.trace("Donor is not in context of {}", message.getIdLong());
            analyzer.log(message, SubmitResult.of(SubmitResultType.DONOR_NOT_IN_CONTEXT, Replacement.createMention(donor)));
            if (addEmoji) Messages.markMessage(message, EmojiDebug.DONOR_NOT_IN_CONTEXT);
            return true;
        }

        // Abuse protection: Cooldown
        if (!canVote(message, donor, receiver, guild, settings)) {
            log.trace("Cooldown active on {}", message.getIdLong());
            if (addEmoji) Messages.markMessage(message, EmojiDebug.ONLY_COOLDOWN);
            return true;
        }

        // block outdated ref message
        // Abuse protection: Message age
        if (refMessage != null) {
            if (abuseSettings.isOldMessage(refMessage) && !context.latestMessages(abuseSettings.minMessages())
                                                                  .contains(refMessage)) {
                log.trace("Reference message of {} is outdated", message.getIdLong());
                analyzer.log(message, SubmitResult.of(SubmitResultType.OUTDATED_REFERENCE_MESSAGE));
                if (addEmoji) Messages.markMessage(message, EmojiDebug.TOO_OLD);
                return true;
            }
        }

        // block outdated message
        // Abuse protection: Message age
        if (abuseSettings.isOldMessage(message)) {
            log.trace("Message of {} is outdated", message.getIdLong());
            analyzer.log(message, SubmitResult.of(SubmitResultType.OUTDATED_MESSAGE));
            if (addEmoji) Messages.markMessage(message, EmojiDebug.TOO_OLD);
            return true;
        }


        if (abuseSettings.isReceiverLimit(receiver)) {
            log.trace("Receiver limit is reached on {}", message.getIdLong());
            analyzer.log(message, SubmitResult.of(SubmitResultType.RECEIVER_LIMIT));
            if (addEmoji) Messages.markMessage(message, EmojiDebug.RECEIVER_LIMIT);
            return true;
        }

        if (abuseSettings.isDonorLimit(donor)) {
            log.trace("Donor limit is reached on {}", message.getIdLong());
            analyzer.log(message, SubmitResult.of(SubmitResultType.DONOR_LIMIT));
            if (addEmoji) Messages.markMessage(message, EmojiDebug.DONOR_LIMIT);
            return true;
        }

        return false;
    }

    private boolean isSelfVote(Member donor, Member receiver, Message message) {
        // block self vote
        if (Verifier.equalSnowflake(receiver, donor)) {
            if (lastEasterEggSent.until(Instant.now(), ChronoUnit.MINUTES) > magicImage.magicImageCooldown()
                && ThreadLocalRandom.current().nextInt(magicImage.magicImagineChance()) == 0) {
                lastEasterEggSent = Instant.now();
                //TODO: Escape unknown channel 5
                message.replyEmbeds(new EmbedBuilder()
                               .setImage(magicImage.magicImageLink())
                               .setColor(Color.RED).build())
                       .queue(msg -> msg.delete().queueAfter(
                               magicImage.magicImageDeleteSchedule(), TimeUnit.SECONDS,
                               RestAction.getDefaultSuccess(),
                               ErrorResponseException.ignore(
                                       ErrorResponse.UNKNOWN_MESSAGE,
                                       ErrorResponse.UNKNOWN_CHANNEL,
                                       ErrorResponse.ILLEGAL_OPERATION_ARCHIVED_THREAD))
                       );
            }
            return true;
        }
        return false;
    }

    private boolean log(Guild guild, Member donor, Member receiver, Message message, @Nullable Message refMessage, ThankType type, Settings settings) {
        var repGuild = guilds.guild(guild);
        // try to log reputation
        if (!repGuild.reputation().user(receiver)
                     .addReputation(donor, message, refMessage, type)) {// submit to database failed. Maybe this message was already voted by the user.
            repGuild.reputation().analyzer().log(message, SubmitResult.of(SubmitResultType.ALREADY_PRESENT));
            log.trace("Could not log reputation for message {}. An equal entry was already present.", message.getIdLong());
            return false;
        }

        // mark messages
        Messages.markMessage(message, refMessage, settings);
        // update role

        var newRank = assigner.updateReporting(receiver, message.getGuildChannel());

        // Send level up message
        newRank.ifPresent(rank -> {
            var announcements = repGuild.settings().announcements();
            if (!announcements.isActive()) return;
            var channel = message.getChannel().asGuildMessageChannel();
            if (!announcements.isSameChannel()) {
                channel = guild.getTextChannelById(announcements.channelId());
            }
            if (channel == null || rank.getRole(guild).isEmpty()) return;
            channel.sendMessage(localizer.localize("message.levelAnnouncement", guild,
                           Replacement.createMention(receiver), Replacement.createMention(rank.role().get())))
                   .setAllowedMentions(Collections.emptyList())
                   .queue();
        });
        return true;
    }

    private boolean isTypeDisabled(ThankType type, Reputation reputation) {
        // force settings
        switch (type) {
            case FUZZY -> {
                if (!reputation.isFuzzyActive()) return true;
            }
            case MENTION -> {
                if (!reputation.isMentionActive()) return true;
            }
            case ANSWER -> {
                if (!reputation.isAnswerActive()) return true;
            }
            case REACTION -> {
                if (!reputation.isReactionActive()) return true;
            }
            case EMBED -> {
                if (!reputation.isEmbedActive()) return true;
            }
            case DIRECT -> {
                if (!reputation.isDirectActive()) return true;
            }
            default -> throw new IllegalStateException("Unexpected value: " + type);
        }
        return false;
    }

    public boolean canVote(Message message, Member donor, Member receiver, Guild guild, Settings settings) {
        var repGuild = settings.repGuild();
        var analyzer = repGuild.reputation().analyzer();
        // block cooldown
        var lastRated = guilds.guild(guild).reputation().user(donor).getLastRatedDuration(receiver);
        if (lastRated.toMinutes() < settings.abuseProtection().cooldown()) {
            analyzer.log(message, SubmitResult.of(SubmitResultType.COOLDOWN_ACTIVE, Replacement.createMention(receiver)));
            log.trace("The last rating is too recent. {}/{}", lastRated.toMinutes(),
                    settings.abuseProtection().cooldown());
            return false;
        }

        if (!settings.thanking().receiverRoles().hasRole(receiver)) {
            analyzer.log(message, SubmitResult.of(SubmitResultType.NO_RECEIVER_ROLE, Replacement.createMention(receiver)));
            log.trace("The receiver does not have a receiver role.");
            return false;
        }
        if (!settings.thanking().donorRoles().hasRole(donor)) {
            analyzer.log(message, SubmitResult.of(SubmitResultType.NO_DONOR_ROLE, Replacement.createMention(donor)));
            log.trace("The donor does not have a donor role.");
            return false;
        }

        return true;
    }
}
