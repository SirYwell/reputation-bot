package de.chojo.repbot.commands.thankwords.handler;

import de.chojo.jdautil.interactions.slash.structure.handler.SlashHandler;
import de.chojo.jdautil.localization.util.Format;
import de.chojo.jdautil.localization.util.Replacement;
import de.chojo.jdautil.wrapper.EventContext;
import de.chojo.repbot.dao.provider.Guilds;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Add implements SlashHandler {
    private final Guilds guilds;

    public Add(Guilds guilds) {
        this.guilds = guilds;
    }

    @Override
    public void onSlashCommand(SlashCommandInteractionEvent event, EventContext context) {
        var pattern = event.getOption("pattern").getAsString();
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            event.reply(context.localize("error.invalidRegex"))
                 .setEphemeral(true)
                 .queue();
            return;
        }
        if (guilds.guild(event.getGuild()).settings().thanking().thankwords().add(pattern)) {
            event.reply(context.localize("command.thankwords.add.message.added",
                    Replacement.create("REGEX", pattern, Format.CODE))).queue();
        }
    }
}
