package mil.nga.giat.geowave.format.nyctlc.ingest;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import mil.nga.giat.geowave.core.geotime.index.dimension.LatitudeDefinition;
import mil.nga.giat.geowave.core.geotime.index.dimension.LongitudeDefinition;
import mil.nga.giat.geowave.core.geotime.index.dimension.TemporalBinningStrategy.Unit;
import mil.nga.giat.geowave.core.geotime.store.dimension.*;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.index.ByteArrayUtils;
import mil.nga.giat.geowave.core.index.dimension.BasicDimensionDefinition;
import mil.nga.giat.geowave.core.index.dimension.NumericDimensionDefinition;
import mil.nga.giat.geowave.core.index.sfc.SFCFactory;
import mil.nga.giat.geowave.core.index.sfc.tiered.TieredSFCIndexFactory;
import mil.nga.giat.geowave.core.store.dimension.NumericDimensionField;
import mil.nga.giat.geowave.core.store.index.BasicIndexModel;
import mil.nga.giat.geowave.core.store.index.CommonIndexValue;
import mil.nga.giat.geowave.core.store.index.CustomIdIndex;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import mil.nga.giat.geowave.core.store.spi.DimensionalityTypeOptions;
import mil.nga.giat.geowave.core.store.spi.DimensionalityTypeProviderSpi;
import mil.nga.giat.geowave.format.nyctlc.NYCTLCUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.TimeUnit;

public class NYCTLCDimensionalityTypeProvider implements
		DimensionalityTypeProviderSpi
{
	private final NYCTLCOptions options = new NYCTLCOptions();
	private static final String DEFAULT_NYCTLC_ID_STR = "NYCTLC_IDX";

	public final static ByteArrayId PICKUP_GEOMETRY_FIELD_ID = new ByteArrayId(
			ByteArrayUtils.combineArrays(
					mil.nga.giat.geowave.core.index.StringUtils.stringToBinary(NYCTLCUtils.Field.PICKUP_LOCATION.getIndexedName()),
					new byte[] {
						0,
						0
					}));

	public final static ByteArrayId DROPOFF_GEOMETRY_FIELD_ID = new ByteArrayId(
			ByteArrayUtils.combineArrays(
					mil.nga.giat.geowave.core.index.StringUtils.stringToBinary(NYCTLCUtils.Field.DROPOFF_LOCATION.getIndexedName()),
					new byte[] {
						0,
						0
					}));

	public final static ByteArrayId TIME_OF_DAY_SEC_FIELD_ID = new ByteArrayId(
			ByteArrayUtils.combineArrays(
					mil.nga.giat.geowave.core.index.StringUtils.stringToBinary(NYCTLCUtils.Field.TIME_OF_DAY_SEC.getIndexedName()),
					new byte[] {
						0,
						0
					}));

	public NYCTLCDimensionalityTypeProvider() {}

	@Override
	public String getDimensionalityTypeName() {
		return "nyctlc_sst";
	}

	@Override
	public String getDimensionalityTypeDescription() {
		return "This dimensionality type is sued to index NYCTLC formatted data with indices for pickup location, dropoff location, and time of day.";
	}

	@Override
	public int getPriority() {
		// arbitrary - just lower than spatial so that the default
		// will be spatial over spatial-temporal
		return 5;
	}

	@Override
	public DimensionalityTypeOptions getOptions() {
		return options;
	}

	@Override
	public PrimaryIndex createPrimaryIndex() {
		return internalCreatePrimaryIndex(options);
	}

	private static PrimaryIndex internalCreatePrimaryIndex(
			final NYCTLCOptions options ) {

		final NumericDimensionField[] fields = new NumericDimensionField[] {
			new LongitudeField(
					PICKUP_GEOMETRY_FIELD_ID),
			new LatitudeField(
					true,
					PICKUP_GEOMETRY_FIELD_ID),
			new LongitudeField(
					DROPOFF_GEOMETRY_FIELD_ID),
			new LatitudeField(
					true,
					DROPOFF_GEOMETRY_FIELD_ID),
			new TimeField(
					new BasicDimensionDefinition(
							0,
							new Long(
									TimeUnit.DAYS.toSeconds(1)).doubleValue()),
					TIME_OF_DAY_SEC_FIELD_ID)
		};

		final NumericDimensionDefinition[] dimensions = new NumericDimensionDefinition[] {
			new PickupLongitudeDefinition(),
			new PickupLatitudeDefinition(
					true),
			new DropoffLongitudeDefinition(),
			new DropoffLatitudeDefinition(
					true),
			new BasicDimensionDefinition(
					0,
					new Long(
							TimeUnit.DAYS.toSeconds(1)).doubleValue())
		};

		final String combinedId = DEFAULT_NYCTLC_ID_STR + "_" + options.bias;

		return new CustomIdIndex(
				TieredSFCIndexFactory.createDefinedPrecisionTieredStrategy(
						dimensions,
						new int[][] {
							new int[] {
								0,
								options.bias.getSpatialPrecision()
							},
							new int[] {
								0,
								options.bias.getSpatialPrecision()
							},
							new int[] {
								0,
								options.bias.getSpatialPrecision()
							},
							new int[] {
								0,
								options.bias.getSpatialPrecision()
							},
							new int[] {
								0,
								options.bias.getTemporalPrecision()
							}
						},
						SFCFactory.SFCType.HILBERT),
				new BasicIndexModel(
						fields),
				new ByteArrayId(
						combinedId + "_POINTONLY"));
	}

	@Override
	public Class<? extends CommonIndexValue>[] getRequiredIndexTypes() {
		return new Class[] {
			GeometryWrapper.class,
			Time.class
		};
	}

	private static class NYCTLCOptions implements
			DimensionalityTypeOptions
	{
		@Parameter(names = {
			"--bias"
		}, required = false, description = "The bias of the index. There can be more precision given to time or space if necessary.", converter = BiasConverter.class)
		protected Bias bias = Bias.BALANCED;
	}

	public static enum Bias {
		TEMPORAL,
		BALANCED,
		SPATIAL;
		// converter that will be used later
		public static Bias fromString(
				final String code ) {

			for (final Bias output : Bias.values()) {
				if (output.toString().equalsIgnoreCase(
						code)) {
					return output;
				}
			}

			return null;
		}

		protected int getSpatialPrecision() {
			switch (this) {
				case SPATIAL:
					return 25;
				case TEMPORAL:
					return 10;
				case BALANCED:
				default:
					return 20;
			}
		}

		protected int getTemporalPrecision() {
			switch (this) {
				case SPATIAL:
					return 10;
				case TEMPORAL:
					return 40;
				case BALANCED:
				default:
					return 20;
			}
		}
	}

	public static class BiasConverter implements
			IStringConverter<Bias>
	{
		@Override
		public Bias convert(
				final String value ) {
			final Bias convertedValue = Bias.fromString(value);

			if (convertedValue == null) {
				throw new ParameterException(
						"Value " + value + "can not be converted to an index bias. " + "Available values are: " + StringUtils.join(
								Bias.values(),
								", ").toLowerCase());
			}
			return convertedValue;
		}

	}

	public static class UnitConverter implements
			IStringConverter<Unit>
	{

		@Override
		public Unit convert(
				final String value ) {
			final Unit convertedValue = Unit.fromString(value);

			if (convertedValue == null) {
				throw new ParameterException(
						"Value " + value + "can not be converted to Unit. " + "Available values are: " + StringUtils.join(
								Unit.values(),
								", ").toLowerCase());
			}
			return convertedValue;
		}
	}

	public static class NYCTLCIndexBuilder
	{
		private final NYCTLCOptions options;

		public NYCTLCIndexBuilder() {
			options = new NYCTLCOptions();
		}

		private NYCTLCIndexBuilder(
				final NYCTLCOptions options ) {
			this.options = options;
		}

		public NYCTLCIndexBuilder setBias(
				final Bias bias ) {
			options.bias = bias;
			return new NYCTLCIndexBuilder(
					options);
		}

		public PrimaryIndex createIndex() {
			return internalCreatePrimaryIndex(options);
		}
	}

	public static class PickupLongitudeDefinition extends
			LongitudeDefinition
	{
		public PickupLongitudeDefinition() {}
	}

	public static class PickupLatitudeDefinition extends
			LatitudeDefinition
	{
		public PickupLatitudeDefinition() {}

		public PickupLatitudeDefinition(
				boolean useHalfRange ) {
			super(
					useHalfRange);
		}
	}

	public static class DropoffLongitudeDefinition extends
			LongitudeDefinition
	{
		public DropoffLongitudeDefinition() {}
	}

	public static class DropoffLatitudeDefinition extends
			LatitudeDefinition
	{
		public DropoffLatitudeDefinition() {}

		public DropoffLatitudeDefinition(
				boolean useHalfRange ) {
			super(
					useHalfRange);
		}
	}
}