package com.barretta.elastic.opensky

import com.elastic.barretta.clients.ESClient
import groovy.transform.Synchronized
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsPool
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.builder.SearchSourceBuilder

import java.util.concurrent.ConcurrentLinkedQueue

@Slf4j
class CollectorRunnable implements Runnable {
    @Override
    void run() {
        def esClient = Manager.getEsClient()
        try {
            def rawRecords = collectRawRecords()
            esClient.bulk([(ESClient.BulkOps.INSERT): rawRecords], PropertyManager.instance.properties.indices.opensky)
            esClient.bulk([(ESClient.BulkOps.INSERT): updateFlightTracks(rawRecords, esClient)], PropertyManager.instance.properties.indices.flight_tracks)
            esClient.close()
        } catch (e) {
            log.error("piss", e)
        } finally {
            esClient.close()
        }
    }

    @Synchronized
    static List updateFlightTracks(records, ESClient client) {
        def tracks = [] as ConcurrentLinkedQueue

        GParsPool.withPool {
            client.config.index = PropertyManager.instance.properties.indices.flight_tracks

            //gather _ids for any existing tracks for this batch of records
            def trackIds = getFlightTracks(records, client)
//            def landedIds = getLandedFlights(records, client)

            //build up our new tracks
            records.eachParallel { record ->
                def skip = false
                def track = [
                    icao: record.icao,
                    landed: record.state.onGround,
                    aircraft: [
                        manufacturerName: record?.aircraft?.manufacturerName,
                        model: record?.aircraft?.model,
                        operatorCallsign: record?.aircraft?.operatorCallsign,
                        owner: record?.aircraft?.owner,
                        registration: record?.aircraft?.registration
                    ],
                    state: [
                        callsign: record.state.callsign,
                        squawk: record.state.squawk,
                        originCountry: record.state.originCountry,
                        onGround: record.state.onGround
                    ],
                    lastUpdate: record.state.timePosition
                ]
                if (record.flight) {
                    track << [
                        departureAirport: record.flight.estDepartureAirport,
                        arrivalAirport  : record.flight.estArrivalAirport,
                        firstSeen       : record.flight.firstSeen,
                        lastSeen        : record.flight.lastSeen,
                        flightTimeMin   : (record.flight.lastSeen - record.flight.firstSeen) / 60 //convert to minutes
                    ]
                }
                def coordinates = []

                //add points to existing track, if we have one
                if (trackIds.containsKey(record.icao)) {
                    def hit = trackIds.get(record.icao)
                    coordinates = hit.track.coordinates
                    track << [_id: hit._id]

                    //we don't want to overwrite our record with empties, so if we didn't get a flight record this time, use existing values
                    if (!track.departureAirport && hit.departureAirport) {
                        track << [
                            departureAirport: hit.departureAirport,
                            arrivalAirport  : hit.arrivalAirport,
                            firstSeen       : hit.firstSeen,
                            lastSeen        : hit.lastSeen,
                            flightTimeMin   : (hit.lastSeen - hit.firstSeen) / 60 //convert to minutes
                        ]
                    }

                    //we only want to add this point if it's actually different from the previous one
                    if (record.state.location.lon != coordinates.last()[0] && record.state.location.lat != coordinates.last()[1]) {
                        coordinates << [record.state.location.lon, record.state.location.lat]
                    } else {
                        skip = true
                    }
                }

                //if we don't have an existing track record, we'll actually need to add this one point twice since you
                //can't have a one-point line
                else {
                    //if we don't have an existing non-landed track, but this is a landed flight, that means it hasn't
                    //taken off again, so we don't need to write the record
                    if (record.state.onGround) {
                        skip = true
                    }
                    coordinates << [record.state.location.lon, record.state.location.lat]
                    coordinates << [record.state.location.lon, record.state.location.lat]
                }

                //little hacky cleaning
                coordinates.removeAll { it[0] == 0.0 || it[1] == 0.0 }

                if (coordinates.size() > 0 && !skip) {
                    track << [track: [type: "LineString", coordinates: coordinates]]
                    tracks.add(track)
                }
            }
        }
        return tracks.toList()
    }

    static List collectRawRecords() {
        log.trace("fetching all states")
        def allStates = OpenSkyNetworkClient.getAllStates()
        log.trace(" ...found [${allStates.states.size()}]")

        log.trace("fetching all flights")
        def allFlights = OpenSkyNetworkClient.getAllFlights(allStates.time)
        log.trace(" ...found [${allFlights.flights.size()}]")

        log.trace("joining everything together")
        def esRecords = allStates.states.inject([]) { list, state ->

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
            def flight = allFlights.flights.find { it.icao == state.icao }
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

            list << record
        }
        log.info("Collected { states: [${allStates.states.size()}], flights: [${allFlights.flights.size()}] }")
        return esRecords
    }

    static def getFlightTracks(records, client) {
        def query = new BoolQueryBuilder()
            .filter(QueryBuilders.termsQuery("icao", records.collectParallel { it.icao }))
            .filter(QueryBuilders.termQuery("landed", false))
        def results = [:]
        client.config.index = PropertyManager.instance.properties.indices.flight_tracks
        log.debug("getting flight tracks:\n"+query.toString())
        client.scrollQuery(query, 5000, 4, 1) {
            def source = it.sourceAsMap
            source["_id"] = it.id
            results << [(source.icao): source]
        }

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
