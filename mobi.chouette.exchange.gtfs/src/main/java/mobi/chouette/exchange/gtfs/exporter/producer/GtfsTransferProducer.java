/**
 * Projet CHOUETTE
 *
 * ce projet est sous license libre
 * voir LICENSE.txt pour plus de details
 *
 */

package mobi.chouette.exchange.gtfs.exporter.producer;

import lombok.extern.log4j.Log4j;
import mobi.chouette.exchange.gtfs.model.GtfsTransfer;
import mobi.chouette.exchange.gtfs.model.exporter.GtfsExporterInterface;
import mobi.chouette.model.ConnectionLink;
import mobi.chouette.model.Interchange;

/**
 * convert Timetable to Gtfs Calendar and CalendarDate
 * <p>
 * optimise multiple period timetable with calendarDate inclusion or exclusion
 */
@Log4j
public class GtfsTransferProducer extends AbstractProducer {
	public GtfsTransferProducer(GtfsExporterInterface exporter) {
		super(exporter);
	}

	private GtfsTransfer transfer = new GtfsTransfer();

   public boolean save(ConnectionLink neptuneObject, String prefix, boolean keepOriginalId){
   transfer.clear();
      transfer.setFromStopId(toGtfsId(neptuneObject.getStartOfLink()
            .getObjectId(),prefix,keepOriginalId));
      transfer
            .setToStopId(toGtfsId(neptuneObject.getEndOfLink().getObjectId(),prefix, keepOriginalId));
      if (neptuneObject.getDefaultDuration() != null
            && neptuneObject.getDefaultDuration().getSeconds() > 1) {
         transfer.setMinTransferTime((int)neptuneObject.getDefaultDuration().getSeconds());
         transfer.setTransferType(GtfsTransfer.TransferType.Minimal);
      } else
      {
			transfer.setTransferType(GtfsTransfer.TransferType.Recommended);
         }

		try {
			getExporter().getTransferExporter().export(transfer);
		} catch (Exception e) {
			log.error("fail to produce transfer " + e.getClass().getName() + " " + e.getMessage());
			return false;
		}
		return true;
	}

	public boolean save(Interchange neptuneObject, String prefix, boolean keepOriginalId) {
		transfer.clear();
		if (neptuneObject.getFeederStopPoint()!=null) {
			transfer.setFromStopId(toGtfsId(neptuneObject.getFeederStopPoint().getContainedInStopAreaRef()
					.getObjectId(), prefix, keepOriginalId));
		}
		if (neptuneObject.getConsumerStopPoint()!=null) {
			transfer
					.setToStopId(toGtfsId(neptuneObject.getConsumerStopPoint().getContainedInStopAreaRef().getObjectId(), prefix, keepOriginalId));
		}
		if (Boolean.TRUE.equals(neptuneObject.getGuaranteed())) {
			transfer.setTransferType(GtfsTransfer.TransferType.Timed);
		} else if (neptuneObject.getMinimumTransferTime() !=null){
         transfer.setTransferType(GtfsTransfer.TransferType.Minimal);
         transfer.setMinTransferTime(Integer.valueOf((int) (neptuneObject.getMinimumTransferTime().getSeconds())));
		} else if (neptuneObject.getPriority() != null && neptuneObject.getPriority() < 0){
			transfer.setTransferType(GtfsTransfer.TransferType.NoAllowed);
		} else {
			transfer.setTransferType(GtfsTransfer.TransferType.Recommended);
		}

		if (neptuneObject.getFeederVehicleJourney()!=null) {
			transfer.setFromTripId(toGtfsId(neptuneObject.getFeederVehicleJourney().getObjectId(), prefix, keepOriginalId));
		}
//		transfer.setFromRouteId(
//				toGtfsId(neptuneObject.getFeederVehicleJourney().getRoute().getLine().getObjectId(), prefix, keepOriginalId));

		if (neptuneObject.getConsumerVehicleJourney()!=null) {
			transfer.setToTripId(toGtfsId(neptuneObject.getConsumerVehicleJourney().getObjectId(), prefix, keepOriginalId));
		}
//		transfer.setToRouteId(
//				toGtfsId(neptuneObject.getConsumerVehicleJourney().getRoute().getLine().getObjectId(), prefix, keepOriginalId));

		try {
         getExporter().getTransferExporter().export(transfer);
      }
      catch (Exception e)
      {
          log.error("fail to produce transfer "+e.getClass().getName()+" "+e.getMessage(), e);
         return false;
      }
      return true;
   }

}
