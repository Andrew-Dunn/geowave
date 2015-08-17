package mil.nga.giat.geowave.datastore.accumulo.cli;

import mil.nga.giat.geowave.core.cli.CLIOperationDriver;
import mil.nga.giat.geowave.core.store.CloseableIterator;
import mil.nga.giat.geowave.core.store.DataStore;
import mil.nga.giat.geowave.core.store.adapter.DataAdapter;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatistics;
import mil.nga.giat.geowave.core.store.adapter.statistics.DataStatisticsStore;
import mil.nga.giat.geowave.core.store.index.IndexStore;

/**
 *
 * Simple command line tool to print statistics to the standard output
 *
 */
public class DumpStatsOperation extends
		StatsOperation implements
		CLIOperationDriver
{
	public boolean doWork(
			final DataStatisticsStore statsStore,
			final DataStore dataStore,
			final IndexStore indexStore,
			final DataAdapter<?> adapter,
			final String[] authorizations ) {
		try (CloseableIterator<DataStatistics<?>> statsIt = statsStore.getAllDataStatistics(authorizations)) {
			while (statsIt.hasNext()) {
				final DataStatistics<?> stats = statsIt.next();
				if ((adapter != null) && !stats.getDataAdapterId().equals(
						adapter.getAdapterId())) {
					continue;
				}
				try {
					System.out.println(stats.toString());
				}
				catch (final Exception ex) {
					LOGGER.error(
							"Malformed statistic",
							ex);
				}
			}
		}
		catch (final Exception ex) {
			LOGGER.error(
					"Error while dumping statistics.",
					ex);
			return false;
		}
		return true;
	}

}