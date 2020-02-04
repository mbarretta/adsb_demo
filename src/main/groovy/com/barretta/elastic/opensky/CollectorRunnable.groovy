package com.barretta.elastic.opensky

import com.barretta.elastic.clients.ESClient
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsPool
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.builder.SearchSourceBuilder

import java.util.concurrent.ConcurrentHashMap

@Slf4j
class CollectorRunnable implements Runnable {
    ESClient esClient

    CollectorRunnable(ESClient client) {
        esClient = client
    }

    @Override
    void run() {
        log.info("Starting collection")

        GParsPool.withPool {
            try {
                def rawRecords = collectRawRecords()
                esClient.bulk([(ESClient.BulkOps.INSERT): rawRecords], PropertyManager.instance.properties.indices.opensky)
                esClient.bulk(updateFlightTracks(rawRecords), PropertyManager.instance.properties.indices.flight_tracks)
            } catch (e) {
                log.error("doh [$e.cause] [$e.message]", e)
            }
        }
        // todo maybe something for landed flights that tries to determine departure and arrival airports by
        // searching for those within some small radius of our first/last points
    }

    static Map<ESClient.BulkOps, List<Map>> updateFlightTracks(List records) {

        //init our bulk op holder
        def bulk = [:] as ConcurrentHashMap
        bulk[ESClient.BulkOps.CREATE] = []
        bulk[ESClient.BulkOps.UPDATE] = []

        //gather _ids for any existing tracks for this batch of records
        def trackIds = getFlightTracks(records)

        //build up our new tracks
        records.eachParallel { record ->
            log.trace("updating track for [$record.icao]")
            def skip = false
            def bulkOp = ESClient.BulkOps.UPDATE

            //we get bad data that say the plane is on the ground, but it's still surely flying
            def reallyLanded = record.state.onGround && record.state.geoAltitude < 50 && record.state.velocity < 70 && record.state.verticalRate == 0

            def coordinates = []
            def track = [lastUpdate: record.state.timePosition]

            //add points to existing track, if we have one
            if (trackIds.containsKey(record.icao)) {
                def hit = trackIds.get(record.icao)
                coordinates = hit.track.coordinates
                track << [_id: hit._id]
                log.trace("existing record: icao [$record.icao] matches id [$hit._id]")

                //if we had no flight info and we see some now, set it
                if (!hit.flight && record.departureAirport) {
                    track << [
                        departureAirport: hit.departureAirport,
                        arrivalAirport  : hit.arrivalAirport,
                    ]
                }
                //if we had no aircraft info and we see some now, set it
                if (!hit.aircraft && record.aircraft) {
                    track << [
                        aircraft: [
                            manufacturerName: record.aircraft?.manufacturerName,
                            model           : record.aircraft?.model,
                            operatorCallsign: record.aircraft?.operatorCallsign,
                            owner           : record.aircraft?.owner,
                            registration    : record.aircraft?.registration
                        ]
                    ]
                }

                //we only want to add this point if it's actually different from the previous one
                if (record.state.location.lon != coordinates.last()[0] && record.state.location.lat != coordinates.last()[1]) {
                    coordinates << [record.state.location.lon, record.state.location.lat]
                } else {
                    skip = true
                }

                if (reallyLanded) {
                    log.debug("flight [${record.icao}] landed")

                    //set "final" bits for the flight
                    track.landed = true
                    track.state = [onGround: true]
                    //not sure why I'm storing this info twice...think for kibana filters so that this will match with flights index schema
                    track.lastSeen = record.state.timePosition
                    track.flightTimeMin = hit.firstSeen ? (track.lastSeen - hit.firstSeen / 60) : null
                }
            }

            //if we don't have an existing track record, we'll actually need to add this one point twice since you
            //can't have a one-point line
            else {
                log.trace("no match for icao [$record.icao]")
                //if we don't have an existing non-landed track, but this is a landed flight, that means it hasn't
                //taken off again, so we don't need to write the record
                if (reallyLanded) {
                    skip = true
                } else {
                    bulkOp = ESClient.BulkOps.CREATE
                    track << [
                        icao     : record.icao,
                        landed   : reallyLanded,
                        firstSeen: record.state.timePosition,
                        aircraft : [
                            manufacturerName: record?.aircraft?.manufacturerName,
                            model           : record?.aircraft?.model,
                            operatorCallsign: record?.aircraft?.operatorCallsign,
                            owner           : record?.aircraft?.owner,
                            registration    : record?.aircraft?.registration
                        ],
                        state    : [
                            callsign     : record.state.callsign,
                            squawk       : record.state.squawk,
                            originCountry: record.state.originCountry,
                            onGround     : reallyLanded
                        ]
                    ]
                    if (record.flight) {
                        track << [
                            departureAirport: record.flight.estDepartureAirport,
                            arrivalAirport  : record.flight.estArrivalAirport
                        ]
                    }
                    coordinates << [record.state.location.lon, record.state.location.lat]
                    coordinates << [record.state.location.lon, record.state.location.lat]
                }
            }

            //little hacky cleaning
            coordinates.removeAll { it[0] == 0.0 || it[1] == 0.0 }

            if (coordinates.size() > 0 && !skip) {
                track << [track: [type: "LineString", coordinates: coordinates]]
                bulk[bulkOp] << track
            }
        }

        return bulk
    }

    static def collectRawRecords() {
        log.trace("fetching all states")
        final def allStates = OpenSkyNetworkClient.getAllStates()
        log.trace(" ...found [${allStates.states.size()}]")

        log.trace("fetching all flights")
        final def allFlights = OpenSkyNetworkClient.getAllFlights(allStates.time)
        log.trace(" ...found [${allFlights.flights.size()}]")

        log.trace("joining everything together")
        def esRecords = Collections.synchronizedList(new ArrayList())
        allStates.states.eachParallel { state ->
            log.trace("joining records for icao [$state.icao]")
            /*
            we're doing some field name transformation here as well so that state data has a "state." prefix,
            flight data has "flight.", etc...
            */

            def record = [icao: state.icao, _id: state._id]

            //add state data
            def stateFields = state.properties
            stateFields.remove("icao")
            stateFields.remove("class")
            stateFields.remove("_id")
            record << [state: stateFields]

            //join flight data
            def flight = allFlights.flights ? allFlights.flights.findParallel { it.icao == state.icao } : null
            if (flight) {
                def flightFields = flight.properties
                flightFields.remove("icao")
                flightFields.remove("class")
                record << [flight: flightFields]
            }

            //join aircraft data
            if (Manager.aircraft.containsKey(state.icao)) {
                record += Manager.aircraft.get(state.icao)
            }

            esRecords << record
        }
        log.info("Collected { states: [${allStates.states.size()}], flights: [${allFlights.flights.size()}] }")

        return esRecords
    }

    static def getFlightTracks(records) {
        def localEsClient = Manager.esClient
        localEsClient.config.index = PropertyManager.instance.properties.indices.flight_tracks

        def query = new BoolQueryBuilder()
            .filter(QueryBuilders.termsQuery("icao", records.collect { it.icao }))
            .filter(QueryBuilders.termQuery("landed", false))
//        log.trace("getting flight tracks:\n" + query.toString())

        def results = [:] as ConcurrentHashMap
        localEsClient.scrollQuery(query, 1500, 4, 1) {
            def source = it.sourceAsMap
            source["_id"] = it.id
            results << [(source.icao): source]
        }
        log.debug("found [${results.size()}] flight tracks")
        return results
    }

    static def getLandedFlights(records, client) {
        def query = new BoolQueryBuilder()
            .filter(QueryBuilders.termsQuery("icao", records.collectParallel { it.icao }))
            .filter(QueryBuilders.termQuery("landed", true))

        def searchRequest = new SearchRequest(PropertyManager.instance.properties.indices.flight_tracks as String)
            .source(new SearchSourceBuilder().query(query))

        return client.search(searchRequest, RequestOptions.DEFAULT)
            .hits
            .collectEntries {
                def source = it.sourceAsMap
                source["_id"] = it.id
                return [(source.icao): source]
            }
    }
}
