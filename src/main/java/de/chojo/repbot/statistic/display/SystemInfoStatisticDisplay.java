package de.chojo.repbot.statistic.display;

import de.chojo.repbot.statistic.EmbedDisplay;
import de.chojo.repbot.statistic.element.DataStatistic;
import de.chojo.repbot.statistic.element.ShardCountStatistic;
import net.dv8tion.jda.api.EmbedBuilder;

public record SystemInfoStatisticDisplay(ShardCountStatistic shardCountStatistic,
                                         DataStatistic dataStatistic) implements EmbedDisplay {

    @Override
    public void appendTo(EmbedBuilder embedBuilder) {
        embedBuilder.setTitle("System Info")
                    .appendDescription(
                            String.format("Watching %s guilds on %s shard/s\n%s/%s active channel on %s active guilds.",
                                    dataStatistic.guilds(), shardCountStatistic.shardCount(),
                                    dataStatistic.activeChannel(), dataStatistic.channel(), dataStatistic.activeGuilds()));
    }
}
