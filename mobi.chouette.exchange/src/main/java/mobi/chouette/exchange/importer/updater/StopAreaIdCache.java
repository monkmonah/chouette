package mobi.chouette.exchange.importer.updater;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.ConcurrencyManagementType;
import javax.ejb.Singleton;

import lombok.extern.log4j.Log4j;

import org.apache.commons.lang3.StringUtils;
import org.rutebanken.netex.model.StopTypeEnumeration;

// TODO replace local cache in StopPlaceRegistryIdValidator with this
@Log4j
@ConcurrencyManagement(ConcurrencyManagementType.BEAN)
@Singleton(name = StopAreaIdCache.BEAN_NAME)
public class StopAreaIdCache {
    public static final String BEAN_NAME = "StopAreaIdCache";

    public static final String ET_CLIENT_NAME_HEADER = "ET-Client-Name";
    public static final String ET_CLIENT_ID_HEADER = "ET-Client-ID";
    public static final String ET_CLIENT_ID = System.getenv("HOSTNAME");
    public static final String ET_CLIENT_NAME = "chouette";


    private Map<String, Map<StopTypeEnumeration, String>> stopPlaceIdMap = new HashMap<>();

    private Map<String, Map<StopTypeEnumeration, String>> quayIdMap = new HashMap<>();

    private String quayEndpoint;

    private String stopPlaceEndpoint;

    private long lastUpdated = 0;

    public long timeToLiveMs;

    @PostConstruct
    public void init() {
        String quayEndpointPropertyKey = "iev.stop.place.register.mapping.quay";
        quayEndpoint = System.getProperty(quayEndpointPropertyKey);
        if (quayEndpoint == null) {
            log.warn("Could not find property named " + quayEndpointPropertyKey + " in iev.properties");
            quayEndpoint = "https://api.entur.org/stop_places/1.0/mapping/quay?recordsPerRoundTrip=220000&includeStopType=true&includeFuture=true";
        }

        String stopPlaceEndpointPropertyKey = "iev.stop.place.register.mapping.stopplace";
        stopPlaceEndpoint = System.getProperty(stopPlaceEndpointPropertyKey);
        if (stopPlaceEndpoint == null) {
            log.warn("Could not find property named " + stopPlaceEndpointPropertyKey + " in iev.properties");
            stopPlaceEndpoint = "https://api.entur.org/stop_places/1.0/mapping/stop_place?recordsPerRoundTrip=220000&includeStopType=true&includeFuture=true";
        }

        String stopPlaceTtlPropertyKey = "iev.stop.place.register.mapping.ttl.ms";
        if (System.getProperty(stopPlaceTtlPropertyKey) != null) {
            try {
                timeToLiveMs = Long.valueOf(System.getProperty(stopPlaceTtlPropertyKey));
            } catch (NumberFormatException nfe) {
                log.warn("Illegal value for property named " + stopPlaceTtlPropertyKey + " in iev.properties. Should be no of milliseconds ttl (long)");
            }
        }
        if (timeToLiveMs < 1) {
            timeToLiveMs = 1000 * 60 * 10; // 10 minutes
            log.warn("Could not find valid property named " + stopPlaceTtlPropertyKey + " in iev.properties, using default value of ms: "+ timeToLiveMs);

        }
    }

    public Map<StopTypeEnumeration, String> getStopPlaceMapping(String localId) {
        ensureCacheIsValid();
        return stopPlaceIdMap.get(localId);
    }

    public Map<StopTypeEnumeration, String> getQuayMapping(String localId) {
        ensureCacheIsValid();
        return quayIdMap.get(localId);
    }

    private synchronized void ensureCacheIsValid() {
        if (lastUpdated < System.currentTimeMillis() - timeToLiveMs) {
            refreshCache();
        }
    }

    private void refreshCache() {
        int remainingUpdateRetries = 10;

        boolean result = false;

        while (!result && remainingUpdateRetries-- > 0) {
            // Fetch data and populate caches
            log.info("Cache is old, refreshing quay and stopplace cache");

            Map<String, Map<StopTypeEnumeration, String>> newStopPlaceIdMap = new HashMap<>();
            boolean stopPlaceOk = populateCache(newStopPlaceIdMap, stopPlaceEndpoint);
            stopPlaceIdMap = newStopPlaceIdMap;

            Map<String, Map<StopTypeEnumeration, String>> newQuayIdMap = new HashMap<>();
            boolean quayOK = populateCache(newQuayIdMap, quayEndpoint);
            quayIdMap = newQuayIdMap;

            if (quayOK && stopPlaceOk) {
                lastUpdated = System.currentTimeMillis();
                result = true;
                log.info("Updated id caches. Current size: stop places: " + stopPlaceIdMap.size() + ", quays=" + quayIdMap.size());
            } else {
                log.error("Error updating caches, retries left = " + remainingUpdateRetries);
                result = false;

                // TODO dodgy
                try {
                    Thread.sleep(10 * 1000);
                } catch (InterruptedException e) {
                    // Swallow
                }
            }

        }

        if (result == false) {
            throw new RuntimeException("Could not update quay cache - cannot validate");
        }
    }


    private boolean populateCache(Map<String, Map<StopTypeEnumeration, String>> cache, String u) {
        cache.clear();
        HttpURLConnection connection = null;

        try {
            URL url = new URL(u);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty(ET_CLIENT_NAME_HEADER, ET_CLIENT_NAME);
            connection.setRequestProperty(ET_CLIENT_ID_HEADER, ET_CLIENT_ID);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.connect();

            // Get Response
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            String line;
            // Map containing validFrom date for current mappings.
            Map<String, Map<StopTypeEnumeration, String>> mostRecentValidFromPerIdPerType = new HashMap<>();

            boolean includeStopType = u.contains("includeStopType=true");
            boolean includeFuture = u.contains("includeFuture=true");

            while ((line = rd.readLine()) != null) {
                String[] split = StringUtils.split(line, ",");
                String localId = split[0];
                StopTypeEnumeration stopPlaceType = null;
                String nsrId = null;
                String validFrom = null;

                if (includeStopType) {
                    stopPlaceType = parseStopPlaceType(split[1]);
                    nsrId = split[2];
                    if (includeFuture){
                        validFrom = split[3];
                    }
                } else {
                    nsrId = split[1];
                    if (includeFuture) {
                        validFrom = split[2];
                    } else {
                        log.error("NSR contains illegal mappings: " + u + " " + line);
                    }
                }
                if (localId != null) {

                    // Use mapping unless there existing a current mapping with an earlier validFrom timestamp
                    mostRecentValidFromPerIdPerType.putIfAbsent(localId, new HashMap<>());

                    String prevValidFrom = mostRecentValidFromPerIdPerType.get(localId).get(stopPlaceType);
                    if (prevValidFrom==null || sortsBefore(validFrom, prevValidFrom)) {
                        mostRecentValidFromPerIdPerType.get(localId).put(stopPlaceType, validFrom);
                        cache.putIfAbsent(localId, new HashMap<>());
                        String prevVal = cache.get(localId).put(stopPlaceType, nsrId);
                        if (prevVal != null && !prevVal.equals(nsrId)) {
                            log.debug("NSR contained Multiple mappings for localId:" + localId + " and stopPlaceType: " + stopPlaceType + ", discarding: " + prevVal + "(from: " + prevValidFrom + ") in favor of: " + nsrId + "(from: " + validFrom + ")");
                        }
                    }


                }
            }
            rd.close();
            return true;
        } catch (Exception e) {
            log.error("Error getting NSR cache for url " + u, e);
        } finally {
            connection.disconnect();
        }

        return false;
    }

    private StopTypeEnumeration parseStopPlaceType(String value) {
        if (value == null) {
            return null;
        }
        return StopTypeEnumeration.fromValue(value);
    }

    /**
     * Compare lexigraphical sorting of the strings.
     *
     * @return true if timestamp  is less than otherTimestamp.
     */
    private boolean sortsBefore(String string, String otherString) {
        if (string == null) {
            return true;
        } else if (otherString == null) {
            return false;
        }
        return string.compareTo(otherString) <= 0;
    }


}
