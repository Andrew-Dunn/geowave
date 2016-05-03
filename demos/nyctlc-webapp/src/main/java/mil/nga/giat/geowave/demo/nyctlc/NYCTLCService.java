package mil.nga.giat.geowave.demo.nyctlc;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKTReader;

import mil.nga.giat.geowave.core.store.CloseableIterator;
import mil.nga.giat.geowave.core.store.query.Query;
import mil.nga.giat.geowave.core.store.query.QueryOptions;
import mil.nga.giat.geowave.datastore.accumulo.AccumuloDataStore;
import mil.nga.giat.geowave.datastore.accumulo.AccumuloOperations;
import mil.nga.giat.geowave.datastore.accumulo.BasicAccumuloOperations;
import mil.nga.giat.geowave.format.nyctlc.NYCTLCUtils;
import mil.nga.giat.geowave.format.nyctlc.adapter.NYCTLCDataAdapter;
import mil.nga.giat.geowave.format.nyctlc.ingest.NYCTLCDimensionalityTypeProvider;
import mil.nga.giat.geowave.format.nyctlc.query.NYCTLCAggregation;
import mil.nga.giat.geowave.format.nyctlc.query.NYCTLCQuery;
import mil.nga.giat.geowave.format.nyctlc.statistics.NYCTLCStatistics;
import mil.nga.giat.geowave.service.ServiceUtils;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.geotools.geometry.jts.GeometryBuilder;

import javax.servlet.ServletConfig;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Produces(MediaType.APPLICATION_JSON)
@Path("/")
public class NYCTLCService
{
	private final static Logger log = Logger.getLogger(NYCTLCService.class);
	private final static int defaultIndentation = 2;

	private String zookeeperUrl;
	private String instanceName;
	private String userName;
	private String password;
	private String tableNamespace;

	// Approximately 2MB in total in memory
	private String boroughs;
	private String neighborhoods;

	private double bufDeg = 0.001;
	private int numSides = 20;
	private GeometryBuilder geomBuilder = new GeometryBuilder();

	private int DEFAULT_TIME_RANGE = new Long(30).intValue();
	private final static String DATE_START_FORMAT = "yyyyMMdd";

	// mapping of addresses/lat,lon pairs to google geocoding address info
	private Map<String, JSONObject> addressMap = new HashMap<String, JSONObject>();

	private AccumuloOperations operations;
	private AccumuloDataStore dataStore;

	public NYCTLCService(
			@Context
			final ServletConfig servletConfig ) {
		final Properties props = ServiceUtils.loadProperties(servletConfig.getServletContext().getResourceAsStream(
				servletConfig.getInitParameter("config.properties")));
		init(
				ServiceUtils.getProperty(
						props,
						"zookeeperUrl"),
				ServiceUtils.getProperty(
						props,
						"instanceName"),
				ServiceUtils.getProperty(
						props,
						"userName"),
				ServiceUtils.getProperty(
						props,
						"password"),
				ServiceUtils.getProperty(
						props,
						"tableNamespace"));
	}

	public NYCTLCService(
			final String zookeeperUrl,
			final String instanceName,
			final String userName,
			final String password,
			final String tableNamespace ) {
		init(
				zookeeperUrl,
				instanceName,
				userName,
				password,
				tableNamespace);
	}

	private void init(
			final String zookeeperUrl,
			final String instanceName,
			final String userName,
			final String password,
			final String tableNamespace ) {

		this.zookeeperUrl = zookeeperUrl;
		this.instanceName = instanceName;
		this.userName = userName;
		this.password = password;
		this.tableNamespace = tableNamespace;

		try {
			operations = new BasicAccumuloOperations(
					zookeeperUrl,
					instanceName,
					userName,
					password,
					tableNamespace);
		}
		catch (Exception e) {
			operations = null;
			log.error(
					"Unable to initialize BasicAccumuloOperations.",
					e);
		}

		if (operations != null) {
			dataStore = new AccumuloDataStore(
					operations);
		}

		try {
			boroughs = IOUtils.toString(Thread.currentThread().getContextClassLoader().getResourceAsStream(
					"nycboroughboundaries.geojson"));
			neighborhoods = IOUtils.toString(Thread.currentThread().getContextClassLoader().getResourceAsStream(
					"pediacitiesnycneighborhoods.geojson"));
		}
		catch (IOException e) {
			log.error(
					"Unable to load geojson resources.",
					e);
		}
	}

	@GET
	@Path("/tripInfo")
	@Produces(MediaType.APPLICATION_JSON)
	public Response tripInfo(
			@DefaultValue("" + Double.MIN_VALUE)
			@QueryParam("startLat")
			double startLat,
			@DefaultValue("" + Double.MIN_VALUE)
			@QueryParam("startLon")
			double startLon,
			@DefaultValue("" + Double.MIN_VALUE)
			@QueryParam("destLat")
			double destLat,
			@DefaultValue("" + Double.MIN_VALUE)
			@QueryParam("destLon")
			double destLon,
			@DefaultValue("")
			@QueryParam("startTime")
			String startTime) throws com.vividsolutions.jts.io.ParseException{
		
		Geometry startGeom = null, destGeom = null;

		// if lat/lon query, do this
		if (startLat != Double.MIN_VALUE && destLat != Double.MIN_VALUE && destLat != Double.MIN_VALUE && destLon != Double.MIN_VALUE) {
			//startGeom = geomBuilder.box(startLon-bufDeg, startLat-bufDeg, startLon+bufDeg, startLat+bufDeg);
			//destGeom = geomBuilder.box(destLon-bufDeg, destLat-bufDeg, destLon+bufDeg, destLat+bufDeg);
			startGeom = new WKTReader().read("POINT(" + startLon + "," + startLat + ")");
			destGeom = new WKTReader().read("POINT(" + destLon + "," + destLat + ")");
		}
		
		int startTimeSec = -1;

		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.Z");
		
		try {
			startTimeSec = (!startTime.isEmpty()) ? dateToTimeOfDaySec(df.parse(startTime)) : dateToTimeOfDaySec(new Date());
		}
		catch (ParseException e1) {
			log.error("Unable to parse start time: " + startTime);
		}

		if (startGeom != null && destGeom != null && startTimeSec >= 0) {
			// run a query using combo of geom & time
			final Query query = new NYCTLCQuery(
					startTimeSec,
					startTimeSec + DEFAULT_TIME_RANGE,
					startGeom,
					destGeom);

			final QueryOptions queryOptions = new QueryOptions();
			queryOptions.setIndex(new NYCTLCDimensionalityTypeProvider().createPrimaryIndex());
			queryOptions.setAggregation(
					new NYCTLCAggregation(),
					new NYCTLCDataAdapter(
							NYCTLCUtils.createPointDataType()));

			final CloseableIterator<NYCTLCStatistics> results = dataStore.query(
					queryOptions,
					query);

			final NYCTLCStatistics stats = (results.hasNext()) ? results.next() : null;

			try {
				results.close();
			}
			catch (IOException e) {
				log.error(
						"Unable to close CloseableIterator.",
						e);
			}

			if (stats != null) {
				final JSONObject result = new JSONObject();
				JSONArray durations = new JSONArray();
				durations.add(stats.getDurationStat().getAvgValue());
				result.put("durations", durations);
				return Response.ok(
						result.toString(defaultIndentation)).build();
			}
		}
		return Response.noContent().build();
	}
	
	@GET
	@Path("/tripInfoExtra")
	@Produces(MediaType.APPLICATION_JSON)
	public Response tripInfoExtra(
			@DefaultValue("" + Double.MIN_VALUE)
			@QueryParam("startLat")
			double startLat,
			@DefaultValue("" + Double.MIN_VALUE)
			@QueryParam("startLon")
			double startLon,
			@DefaultValue("" + Double.MIN_VALUE)
			@QueryParam("destLat")
			double destLat,
			@DefaultValue("" + Double.MIN_VALUE)
			@QueryParam("destLon")
			double destLon,
			@DefaultValue("")
			@QueryParam("startTime")
			String startTime,
			@DefaultValue("")
			@QueryParam("endTime")
			String endTime,
			@DefaultValue("")
			@QueryParam("startAddress")
			String startAddress,
			@DefaultValue("")
			@QueryParam("destAddress")
			String destAddress ) {

		Geometry startGeom = null, destGeom = null;
		JSONObject startAddrInfo = null, destAddrInfo = null;

		// if lat/lon query, do this
		if (startLat != Double.MIN_VALUE && destLat != Double.MIN_VALUE && destLat != Double.MIN_VALUE && destLon != Double.MIN_VALUE) {
			startAddrInfo = getAddressInfo(
					startLon,
					startLat);
			destAddrInfo = getAddressInfo(
					destLon,
					destLat);
			startGeom = geomBuilder.circle(
					startLon,
					startLat,
					bufDeg,
					numSides);
			destGeom = geomBuilder.circle(
					destLon,
					destLat,
					bufDeg,
					numSides);
		}
		// if address query, do this
		else if (!startAddress.isEmpty() && !destAddress.isEmpty()) {
			startAddrInfo = getAddressInfo(startAddress);
			destAddrInfo = getAddressInfo(destAddress);

			startGeom = geometryFromAddrInfo(startAddrInfo);
			destGeom = geometryFromAddrInfo(destAddrInfo);
		}

		int startTimeSec = -1, endTimeSec = -1;

		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.Z");
		
		try {
			startTimeSec = (!startTime.isEmpty()) ? dateToTimeOfDaySec(df.parse(startTime)) : dateToTimeOfDaySec(new Date());
			endTimeSec = (!endTime.isEmpty()) ? dateToTimeOfDaySec(df.parse(endTime)) : dateToTimeOfDaySec(new Date());
		}
		catch (ParseException e1) {
			log.error("Unable to parse start time: " + startTime);
		}

		if (startGeom != null && destGeom != null) {
			// run a query using combo of geom & time
			final Query query = new NYCTLCQuery(
					startTimeSec,
					endTimeSec,
					startGeom,
					destGeom);

			final QueryOptions queryOptions = new QueryOptions();
			queryOptions.setIndex(new NYCTLCDimensionalityTypeProvider().createPrimaryIndex());
			queryOptions.setAggregation(
					new NYCTLCAggregation(),
					new NYCTLCDataAdapter(
							NYCTLCUtils.createPointDataType()));

			final CloseableIterator<NYCTLCStatistics> results = dataStore.query(
					queryOptions,
					query);

			final NYCTLCStatistics stats = (results.hasNext()) ? results.next() : null;

			try {
				results.close();
			}
			catch (IOException e) {
				log.error(
						"Unable to close ColseableIterator.",
						e);
			}

			if (stats != null) {
				final JSONObject result = new JSONObject();
				result.put(
						"stats",
						stats.toJSONObject());
				result.put(
						"startGeom",
						startGeom.toText());
				result.put(
						"destGeom",
						destGeom.toText());
				result.put(
						"startAddressInfo",
						getStreetAddress(startAddrInfo));
				result.put(
						"destAddressInfo",
						getStreetAddress(destAddrInfo));
				return Response.ok(
						result.toString(defaultIndentation)).build();
			}
		}
		return Response.noContent().build();
	}

	private JSONObject getAddressInfo(
			final String address ) {
		if (addressMap.containsKey(address)) {
			return addressMap.get(address);
		}
		else {
			final Client client = ClientBuilder.newClient();

			final WebTarget target = client.target("http://maps.googleapis.com");

			final Response resp = target.path(
					"maps/api/geocode/json").queryParam(
					"address",
					address).request().get();

			if (resp.getStatus() == Status.OK.getStatusCode()) {
				resp.bufferEntity();
				final JSONObject addrInfo = JSONObject.fromObject(resp.readEntity(String.class));
				addressMap.put(
						address,
						addrInfo);
				return addrInfo;
			}
		}
		return null;
	}

	private JSONObject getAddressInfo(
			final double lon,
			final double lat ) {

		if (addressMap.containsKey(lon + "," + lat)) {
			return addressMap.get(lon + "," + lat);
		}
		else {
			final Client client = ClientBuilder.newClient();

			final WebTarget target = client.target("http://maps.googleapis.com");

			final Response resp = target.path(
					"maps/api/geocode/json").queryParam(
					"latlng",
					lat + "," + lon).request().get();

			if (resp.getStatus() == Status.OK.getStatusCode()) {
				resp.bufferEntity();
				final JSONObject addrInfo = JSONObject.fromObject(resp.readEntity(String.class));
				addressMap.put(
						lon + "," + lat,
						addrInfo);
				return addrInfo;
			}
		}
		return null;
	}

	private JSONObject getStreetAddress(
			final JSONObject addrInfo ) {
		final JSONObject address = new JSONObject();
		final JSONArray results = addrInfo.getJSONArray("results");
		for (int resultIdx = 0; resultIdx < results.size(); resultIdx++) {
			final JSONObject result = results.getJSONObject(resultIdx);
			final JSONArray types = result.getJSONArray("types");
			for (int typesIdx = 0; typesIdx < types.size(); typesIdx++) {
				if (types.getString(
						typesIdx).equals(
						"street_address")) {
					address.put(
							"formatted_address",
							result.getString("formatted_address"));
					address.put(
							"address_components",
							result.getJSONArray("address_components"));
					return address;
				}
			}
		}
		address.put(
				"formatted_address",
				"Unknown Address");
		address.put(
				"address_components",
				new JSONArray());
		return address;
	}

	private Geometry geometryFromAddrInfo(
			JSONObject addrInfo ) {

		JSONObject geometry = addrInfo.getJSONArray(
				"results").getJSONObject(
				0).getJSONObject(
				"geometry");

		if (geometry.containsKey("bounds")) {
			double nLat = (double) geometry.getJSONObject(
					"bounds").getJSONObject(
					"northeast").get(
					"lat");
			double eLon = (double) geometry.getJSONObject(
					"bounds").getJSONObject(
					"northeast").get(
					"lng");
			double sLat = (double) geometry.getJSONObject(
					"bounds").getJSONObject(
					"southwest").get(
					"lat");
			double wLon = (double) geometry.getJSONObject(
					"bounds").getJSONObject(
					"southwest").get(
					"lng");

			return geomBuilder.box(
					wLon,
					sLat,
					eLon,
					nLat);
		}
		else if (geometry.containsKey("location")) {
			double lat = (double) geometry.getJSONObject(
					"location").get(
					"lat");
			double lon = (double) geometry.getJSONObject(
					"location").get(
					"lng");
			return geomBuilder.circle(
					lon,
					lat,
					bufDeg,
					numSides);
		}

		return null;
	}

	private int dateToTimeOfDaySec(
			final Date date ) {
		final SimpleDateFormat dateStartFormat = new SimpleDateFormat(
				DATE_START_FORMAT);
		Date dateStart = null;
		try {
			dateStart = dateStartFormat.parse(dateStartFormat.format(date));
		}
		catch (ParseException e) {
			log.error(
					"Unable to parse date.",
					e);
		}
		return new Long(
				TimeUnit.MILLISECONDS.toSeconds(date.getTime() - dateStart.getTime())).intValue();
	}

	@GET
	@Path("/boroughs")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getBoroughs() {
		return Response.ok(
				boroughs).build();
	}

	@GET
	@Path("/neighborhoods")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getNeighborhoods() {
		return Response.ok(
				neighborhoods).build();
	}

	public static void main(
			String[] args ) {
		NYCTLCService service = new NYCTLCService(
				"localhost:2181",
				"geowave",
				"root",
				"geowave",
				"nyctlc");

		Response resp = service.getBoroughs();

		resp = service.getNeighborhoods();

		// resp = service.tripInfo(
		// 40.820701599121094,
		// -73.954818725585937,
		// 40.729896545410156,
		// -73.998832702636719,
		// 0,
		// 15,
		// "",
		// "");

		// resp = service.tripInfo(
		// Double.MIN_VALUE,
		// Double.MIN_VALUE,
		// Double.MIN_VALUE,
		// Double.MIN_VALUE,
		// 0,
		// 15,
		// "5615 Roundtree Lane, Columbia, MD",
		// "43008 Center St., South Riding, VA");

//		resp = service.tripInfo(
//				Double.MIN_VALUE,
//				Double.MIN_VALUE,
//				Double.MIN_VALUE,
//				Double.MIN_VALUE,
//				0,
//				15,
//				"Roundtree Lane, Columbia, MD",
//				"Center St., South Riding, VA");

		System.out.println("done!");
	}
}