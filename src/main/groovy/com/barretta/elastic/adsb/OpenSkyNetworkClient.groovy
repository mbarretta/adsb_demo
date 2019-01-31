package com.barretta.elastic.adsb

import groovy.json.JsonException
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

@Slf4j
class OpenSkyNetworkClient {
    final static String URL = "https://opensky-network.org/api"
    static class AllStateVectorsResponse {
        long time
        List<StateVector> states = []
    }

    static class AllFlightsResponse {
        List<Flight> flights = []
    }

    static class StateVector {
        String icao
        String callsign
        String originCountry
        int timePosition
        int lastContact
        Map<String, Float> location = [:]
        float baroAltitude
        boolean onGround
        float velocity
        float trueTrack
        float verticalRate
        List<Integer> sensors
        float geoAltitude
        String squawk
        boolean spi
        PositionSource positionSource
        String _id
    }

    static class Flight {
        String icao
        int firstSeen
        String estDepartureAirport
        int lastSeen
        String estArrivalAirport
        int estDepartureAirportHorizDistance
        int estDepartureAirportVertDistance
        int estArrivalAirportHorizDistance
        int estArrivalAirportVertDistance
        int departureAirportCandidatesCount
        int arrivalAirportCandidatesCount
    }

    static enum PositionSource {
        ADS_B, ASTERIX, MLAT, OTHER
    }

    static AllStateVectorsResponse getAllStates() {
        def rawResponse = new JsonSlurper().parse("$URL/states/all".toURL())
        def response = new AllStateVectorsResponse(time: rawResponse.time)

        def positionSources = PositionSource.values()
        rawResponse.states.each { state ->
            try {
                def stateVector = new StateVector()
                stateVector.with {
                    icao = state[0]?.trim()
                    callsign = state[1]?.trim()
                    originCountry = state[2]?.trim()
                    timePosition = state[3]?.intValue() ?: 0
                    lastContact = state[4]?.intValue() ?: 0
                    location.lon = state[5] ?: 0f
                    location.lat = state[6] ?: 0f
                    baroAltitude = state[7]?.intValue() ?: 0
                    onGround = state[8]?.booleanValue() ?: false
                    velocity = state[9]?.floatValue() ?: 0f
                    trueTrack = state[10]?.floatValue() ?: 0f
                    verticalRate = state[11]?.floatValue() ?: 0f
                    sensors = state[12] ? state[12]*.intValue() : null
                    geoAltitude = state[13]?.floatValue() ?: 0f
                    squawk = state[14]?.trim()
                    spi = state[15]?.booleanValue() ?: false
                    positionSource = state.size() == 17 ? positionSources[state[16].intValue()] : null
                    _id = lastContact + icao
                }
                response.states << stateVector
            } catch (e) {
                log.error("ERROR parsing state vector:\n" + state.toString(), e)
            }
        }
        return response
    }

    static AllFlightsResponse getAllFlights(time) {
        def response = new AllFlightsResponse()

        //this try{} is a bit ugly, but simple means of dealing with empty results, which end are sent as a 404
        try {
            def rawResponse = new JsonSlurper().parse("$URL/flights/all?begin=${time - 2000}&end=$time".toURL())
            rawResponse?.each { flight ->
                try {
                    def flightObj = new Flight()
                    flightObj.with {
                        icao = flight?.icao24?.trim()
                        firstSeen = flight?.firstSeen?.intValue()
                        estDepartureAirport = flight?.estDepartureAirport?.trim()
                        lastSeen = flight?.lastSeen?.intValue()
                        estArrivalAirport = flight?.estArrivalAirport?.trim()
                        estDepartureAirportHorizDistance = flight?.estDepartureAirportHorizDistance?.intValue()
                        estDepartureAirportVertDistance = flight?.estDepartureAirportVertDistance?.intValue()
                        estArrivalAirportHorizDistance = flight?.estArrivalAirportHorizDistance?.intValue()
                        estArrivalAirportVertDistance = flight?.estArrivalAirportVertDistance?.intValue()
                        departureAirportCandidatesCount = flight?.departureAirportCandidatesCount?.intValue()
                        arrivalAirportCandidatesCount = flight?.arrivalAirportCandidatesCount?.intValue()
                    }
                    response.flights << flightObj
                } catch (e) {
                    log.error("ERROR parsing flight:\n" + flight.toString(), e)
                }
            }
        } catch (JsonException e) {
            log.debug("No data for timespan [${time - 20}] to [$time]", e)
        }
        return response
    }
}
