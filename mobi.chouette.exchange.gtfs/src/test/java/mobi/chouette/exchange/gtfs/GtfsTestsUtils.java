package mobi.chouette.exchange.gtfs;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import mobi.chouette.common.Context;
import mobi.chouette.exchange.report.ReportConstant;
import mobi.chouette.model.AccessLink;
import mobi.chouette.model.AccessPoint;
import mobi.chouette.model.ConnectionLink;
import mobi.chouette.model.JourneyPattern;
import mobi.chouette.model.Line;
import mobi.chouette.model.Route;
import mobi.chouette.model.StopArea;
import mobi.chouette.model.StopPoint;
import mobi.chouette.model.util.Referential;

import org.apache.commons.io.FileUtils;
import java.time.LocalTime;
import org.testng.Assert;

public class GtfsTestsUtils implements Constant, ReportConstant{

	

	protected static final String path = "src/test/data";
	public static  void copyFile(String fileName) throws IOException {
		File srcFile = new File(path, fileName);
		File destFile = new File("target/referential/test", fileName);
		FileUtils.copyFile(srcFile, destFile);
	}

	public static void checkLine(Context context)
	{
		
		// checl line content before save (cause connection links could not be saved
		Referential referential = (Referential) context.get(REFERENTIAL);
		Assert.assertNotNull(referential, "referential");
		Assert.assertEquals(referential.getLines().size(), 1, "lines size");
		Line line = referential.getLines().get("NINOXE:Line:15574334");
		Assert.assertNotNull(line, "line");

		// comptage des objets :
		Assert.assertNotNull(line.getNetwork(), "line must have a network");
		Assert.assertNotNull(line.getGroupOfLines(), "line must have groupOfLines");
		Assert.assertEquals(line.getGroupOfLines().size(), 1, "line must have 1 groupOfLine");
		Assert.assertNotNull(line.getCompany(), "line must have a company");
		Assert.assertNotNull(line.getRoutes(), "line must have routes");
		Assert.assertEquals(line.getRoutes().size(), 4, "line must have 4 routes");
		Set<StopArea> bps = new HashSet<StopArea>();
		Set<StopArea> comms = new HashSet<StopArea>();

		for (Route route : line.getRoutes()) {
			Assert.assertNotNull(route.getJourneyPatterns(), "line routes must have journeyPattens");
			for (JourneyPattern jp : route.getJourneyPatterns()) {
				Assert.assertNotNull(jp.getStopPoints(), "line journeyPattens must have stoppoints");
				for (StopPoint point : jp.getStopPoints()) {

					Assert.assertNotNull(point.getScheduledStopPoint().getContainedInStopAreaRef().getObject(), "stoppoints must have StopAreas");
					bps.add(point.getScheduledStopPoint().getContainedInStopAreaRef().getObject());

					Assert.assertNotNull(point.getScheduledStopPoint().getContainedInStopAreaRef().getObject().getParent(), "StopAreas must have parent : "
							+ point.getScheduledStopPoint().getContainedInStopAreaRef().getObjectId());
					comms.add(point.getScheduledStopPoint().getContainedInStopAreaRef().getObject().getParent());
				}
			}
		}
		Assert.assertEquals(bps.size(), 18, "line must have 18 boarding positions");
		Assert.assertEquals(comms.size(), 9, "line must have 9 commercial stop points");

		Set<ConnectionLink> clinks = new HashSet<ConnectionLink>();
		Set<AccessLink> alinks = new HashSet<AccessLink>();

		for (StopArea comm : comms) {

			if (comm.getConnectionEndLinks() != null) {
				clinks.addAll(comm.getConnectionEndLinks());
			}
			if (comm.getConnectionStartLinks() != null) {
				clinks.addAll(comm.getConnectionStartLinks());
			}
			if (comm.getAccessLinks() != null) {
				alinks.addAll(comm.getAccessLinks());
			}
		}
		Assert.assertEquals(clinks.size(), 2, "line must have 2 connection link");
		for (ConnectionLink connectionLink : clinks) {
			Assert.assertEquals(connectionLink.getDefaultDuration().getSeconds(), 4200l, "line must have links duration of 1 hour and 10 minutes");
			// Reporter.log(connectionLink.toString("\t", 1));

		}
		Assert.assertEquals(alinks.size(), 1, "line must have 1 access link");

		Set<AccessPoint> apoints = new HashSet<AccessPoint>();

		for (AccessLink accessLink : alinks) {
			Assert.assertEquals(accessLink.getDefaultDuration().getSeconds(), 60l, "line must have links duration of 1 minutes");
			// Reporter.log(accessLink.toString("\t", 1));
			apoints.add(accessLink.getAccessPoint());

		}
		Assert.assertEquals(apoints.size(), 1, "line must have 1 access point");
		for (AccessPoint accessPoint : apoints) {
			Assert.assertEquals(accessPoint.getOpeningTime(), LocalTime.of(6,0,0),"line must have opening time of 6 hours");
			Assert.assertEquals(accessPoint.getClosingTime(),LocalTime.of(22,10,0), "line must have closing time of 22 hours 10");
		}

	}

}
