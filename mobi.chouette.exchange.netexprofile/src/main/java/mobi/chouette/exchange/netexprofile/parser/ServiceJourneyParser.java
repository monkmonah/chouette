package mobi.chouette.exchange.netexprofile.parser;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.xml.bind.JAXBElement;

import lombok.extern.log4j.Log4j;
import mobi.chouette.common.Context;
import mobi.chouette.common.TimeUtil;
import mobi.chouette.exchange.importer.Parser;
import mobi.chouette.exchange.importer.ParserFactory;
import mobi.chouette.exchange.netexprofile.Constant;
import mobi.chouette.exchange.netexprofile.importer.NetexprofileImportParameters;
import mobi.chouette.exchange.netexprofile.importer.util.NetexTimeConversionUtil;
import mobi.chouette.model.BookingArrangement;
import mobi.chouette.model.Company;
import mobi.chouette.model.DestinationDisplay;
import mobi.chouette.model.JourneyPattern;
import mobi.chouette.model.StopPoint;
import mobi.chouette.model.Timetable;
import mobi.chouette.model.VehicleJourney;
import mobi.chouette.model.VehicleJourneyAtStop;
import mobi.chouette.model.type.TransportModeNameEnum;
import mobi.chouette.model.util.ObjectFactory;
import mobi.chouette.model.util.ObjectIdTypes;
import mobi.chouette.model.util.Referential;

import org.rutebanken.netex.model.AllVehicleModesOfTransportEnumeration;
import org.rutebanken.netex.model.DayTypeRefStructure;
import org.rutebanken.netex.model.DayTypeRefs_RelStructure;
import org.rutebanken.netex.model.FlexibleServiceProperties;
import org.rutebanken.netex.model.JourneyPatternRefStructure;
import org.rutebanken.netex.model.Journey_VersionStructure;
import org.rutebanken.netex.model.JourneysInFrame_RelStructure;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.TimetabledPassingTime;

@Log4j
public class ServiceJourneyParser extends NetexParser implements Parser, Constant {

	private KeyValueParser keyValueParser = new KeyValueParser();

	private ContactStructureParser contactStructureParser = new ContactStructureParser();

	@Override
	@SuppressWarnings("unchecked")
	public void parse(Context context) throws Exception {
		Referential referential = (Referential) context.get(REFERENTIAL);
		JourneysInFrame_RelStructure journeyStructs = (JourneysInFrame_RelStructure) context.get(NETEX_LINE_DATA_CONTEXT);
		List<Journey_VersionStructure> serviceJourneys = journeyStructs.getVehicleJourneyOrDatedVehicleJourneyOrNormalDatedVehicleJourney();

		for (Journey_VersionStructure journeyStruct : serviceJourneys) {
			if (! (journeyStruct instanceof ServiceJourney)) {
				if(log.isTraceEnabled()) {
					log.trace("Ignoring non-ServiceJourney journey or deadrun with id: " + journeyStruct.getId());
				}
				continue;
			}
			ServiceJourney serviceJourney = (ServiceJourney) journeyStruct;

			VehicleJourney vehicleJourney = ObjectFactory.getVehicleJourney(referential, serviceJourney.getId());

			if (vehicleJourney.isFilled()) {
				VehicleJourney vehicleJourneyWithVersion = ObjectFactory.getVehicleJourney(referential,
						serviceJourney.getId() + "_" + serviceJourney.getVersion());
				log.warn("Already parsed " + vehicleJourney.getObjectId() + ", will use version field as part of id to separate them: "
						+ vehicleJourneyWithVersion.getObjectId());
				vehicleJourney = vehicleJourneyWithVersion;
			}

			DayTypeRefs_RelStructure dayTypes = serviceJourney.getDayTypes();
			if (dayTypes != null) {
				for (JAXBElement<? extends DayTypeRefStructure> dayType : dayTypes.getDayTypeRef()) {
					String timetableId = dayType.getValue().getRef();
					Timetable timetable = ObjectFactory.getTimetable(referential, timetableId);
					vehicleJourney.addTimetable(timetable);
				}
			}

			vehicleJourney.setObjectVersion(NetexParserUtils.getVersion(serviceJourney));

			vehicleJourney.setPublication(NetexParserUtils.toPublicationEnum(serviceJourney.getPublication()));

			vehicleJourney.setPublishedJourneyIdentifier(serviceJourney.getPublicCode());

			if (serviceJourney.getPrivateCode() != null) {
				vehicleJourney.setPrivateCode(serviceJourney.getPrivateCode().getValue());
			}

			if (serviceJourney.getJourneyPatternRef() != null) {
				JourneyPatternRefStructure patternRefStruct = serviceJourney.getJourneyPatternRef().getValue();
				mobi.chouette.model.JourneyPattern journeyPattern = ObjectFactory.getJourneyPattern(referential, patternRefStruct.getRef());
				vehicleJourney.setJourneyPattern(journeyPattern);
			}

			if (serviceJourney.getName() != null) {
				vehicleJourney.setPublishedJourneyName(serviceJourney.getName().getValue());
			} else {
				JourneyPattern journeyPattern = vehicleJourney.getJourneyPattern();
				if (journeyPattern.getDepartureStopPoint() != null) {
					DestinationDisplay dd = journeyPattern.getDepartureStopPoint().getDestinationDisplay();
					if (dd != null) {
						vehicleJourney.setPublishedJourneyName(dd.getFrontText());
					}
				}
			}

			if (serviceJourney.getOperatorRef() != null) {
				String operatorIdRef = serviceJourney.getOperatorRef().getRef();
				Company company = ObjectFactory.getCompany(referential, operatorIdRef);
				vehicleJourney.setCompany(company);
			} else if (serviceJourney.getLineRef() != null) {
				String lineIdRef = serviceJourney.getLineRef().getValue().getRef();
				Company company = ObjectFactory.getLine(referential, lineIdRef).getCompany();
				vehicleJourney.setCompany(company);
			} else {
				Company company = vehicleJourney.getJourneyPattern().getRoute().getLine().getCompany();
				vehicleJourney.setCompany(company);
			}

			if (serviceJourney.getRouteRef() != null) {
				mobi.chouette.model.Route route = ObjectFactory.getRoute(referential, serviceJourney.getRouteRef().getRef());
				vehicleJourney.setRoute(route);
			} else {
				mobi.chouette.model.Route route = vehicleJourney.getJourneyPattern().getRoute();
				vehicleJourney.setRoute(route);
			}

			if (serviceJourney.getTransportMode() != null) {
				AllVehicleModesOfTransportEnumeration transportMode = serviceJourney.getTransportMode();
				TransportModeNameEnum transportModeName = NetexParserUtils.toTransportModeNameEnum(transportMode.value());
				vehicleJourney.setTransportMode(transportModeName);
			}

			vehicleJourney.setTransportSubMode(NetexParserUtils.toTransportSubModeNameEnum(serviceJourney.getTransportSubmode()));

			parseTimetabledPassingTimes(context, referential, serviceJourney, vehicleJourney);

			vehicleJourney.setKeyValues(keyValueParser.parse(serviceJourney.getKeyList()));
			vehicleJourney.setServiceAlteration(NetexParserUtils.toServiceAlterationEum(serviceJourney.getServiceAlteration()));

			if (serviceJourney.getFlexibleServiceProperties() != null) {
				vehicleJourney.setFlexibleService(true);
				mobi.chouette.model.FlexibleServiceProperties chouetteFSP = new mobi.chouette.model.FlexibleServiceProperties();
				FlexibleServiceProperties netexFSP = serviceJourney.getFlexibleServiceProperties();

				chouetteFSP.setObjectId(netexFSP.getId());
				chouetteFSP.setObjectVersion(NetexParserUtils.getVersion(netexFSP));

				chouetteFSP.setChangeOfTimePossible(netexFSP.isChangeOfTimePossible());
				chouetteFSP.setCancellationPossible(netexFSP.isCancellationPossible());
				chouetteFSP.setFlexibleServiceType(NetexParserUtils.toFlexibleServiceType(netexFSP.getFlexibleServiceType()));

				BookingArrangement bookingArrangement = new BookingArrangement();
				if (netexFSP.getBookingNote() != null) {
					bookingArrangement.setBookingNote(netexFSP.getBookingNote().getValue());
				}
				bookingArrangement.setBookingAccess(NetexParserUtils.toBookingAccess(netexFSP.getBookingAccess()));
				bookingArrangement.setBookWhen(NetexParserUtils.toPurchaseWhen(netexFSP.getBookWhen()));
				bookingArrangement.setBuyWhen(netexFSP.getBuyWhen().stream().map(NetexParserUtils::toPurchaseMoment).collect(Collectors.toList()));
				bookingArrangement.setBookingMethods(netexFSP.getBookingMethods().stream().map(NetexParserUtils::toBookingMethod).collect(Collectors.toList()));
				bookingArrangement.setLatestBookingTime(netexFSP.getLatestBookingTime());
				bookingArrangement.setMinimumBookingPeriod(netexFSP.getMinimumBookingPeriod());

				bookingArrangement.setBookingContact(contactStructureParser.parse(netexFSP.getBookingContact()));

				chouetteFSP.setBookingArrangement(bookingArrangement);
				vehicleJourney.setFlexibleServiceProperties(chouetteFSP);
			}
			vehicleJourney.setFilled(true);

		}
	}

	private void parseTimetabledPassingTimes(Context context, Referential referential, ServiceJourney serviceJourney, VehicleJourney vehicleJourney) {

		NetexprofileImportParameters configuration = (NetexprofileImportParameters) context.get(CONFIGURATION);


		for (int i = 0; i < serviceJourney.getPassingTimes().getTimetabledPassingTime().size(); i++) {
			TimetabledPassingTime passingTime = serviceJourney.getPassingTimes().getTimetabledPassingTime().get(i);
			String passingTimeId = passingTime.getId();

			if (passingTimeId == null) {
				// TODO profile should prevent this from happening, creating bogus
				passingTimeId = NetexParserUtils.netexId(configuration.getObjectIdPrefix(), ObjectIdTypes.VEHICLE_JOURNEY_AT_STOP_KEY, UUID.randomUUID().toString());
			}
			VehicleJourneyAtStop vehicleJourneyAtStop = ObjectFactory.getVehicleJourneyAtStop(referential, passingTimeId);
			vehicleJourneyAtStop.setObjectVersion(NetexParserUtils.getVersion(passingTime));

			StopPoint stopPoint = ObjectFactory.getStopPoint(referential, passingTime.getPointInJourneyPatternRef().getValue().getRef());
			vehicleJourneyAtStop.setStopPoint(stopPoint);

			parsePassingTimes(passingTime, vehicleJourneyAtStop);
			vehicleJourneyAtStop.setVehicleJourney(vehicleJourney);
		}

		vehicleJourney.getVehicleJourneyAtStops().sort(Comparator.comparingInt(o -> o.getStopPoint().getPosition()));
	}

	// TODO add support for other time zones and zone offsets, for now only handling UTC
	private void parsePassingTimes(TimetabledPassingTime timetabledPassingTime, VehicleJourneyAtStop vehicleJourneyAtStop) {

		NetexTimeConversionUtil.parsePassingTime(timetabledPassingTime, false, vehicleJourneyAtStop);
		NetexTimeConversionUtil.parsePassingTime(timetabledPassingTime, true, vehicleJourneyAtStop);

		// TODO copying missing data since Chouette pt does not properly support missing values
		if (vehicleJourneyAtStop.getArrivalTime() == null && vehicleJourneyAtStop.getDepartureTime() != null) {
			vehicleJourneyAtStop.setArrivalTime(vehicleJourneyAtStop.getDepartureTime());
			vehicleJourneyAtStop.setArrivalDayOffset(vehicleJourneyAtStop.getDepartureDayOffset());
		} else if (vehicleJourneyAtStop.getArrivalTime() != null && vehicleJourneyAtStop.getDepartureTime() == null) {
			vehicleJourneyAtStop.setDepartureTime(vehicleJourneyAtStop.getArrivalTime());
			vehicleJourneyAtStop.setDepartureDayOffset(vehicleJourneyAtStop.getArrivalDayOffset());
		}

	}

	static {
		ParserFactory.register(ServiceJourneyParser.class.getName(), new ParserFactory() {
			private ServiceJourneyParser instance = new ServiceJourneyParser();

			@Override
			protected Parser create() {
				return instance;
			}
		});
	}

}
