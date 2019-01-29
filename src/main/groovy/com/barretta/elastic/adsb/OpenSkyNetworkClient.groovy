package com.barretta.elastic.adsb

import groovy.json.JsonSlurper

class OpenSkyNetworkClient {
    final static String URL = "https://opensky-network.org/api"
    static class AllStateVectorsResponse {
        long time
        List<StateVector> states = []
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
    }

    static enum PositionSource {
        ADS_B, ASTERIX, MLAT
    }

    static AllStateVectorsResponse getAllStates() {
        def rawResponse = new JsonSlurper().parse("$URL/states/all".toURL())
        def objResponse = new AllStateVectorsResponse(time: rawResponse.time)

        def positionSources = PositionSource.values()
        rawResponse.states.each { state ->
            def stateVector = new StateVector()
            stateVector.with {
                icao = state[0]?.trim()
                callsign = state[1]?.trim()
                originCountry = state[2]?.trim()
                timePosition = state[3]?.intValue() ?: 0
                lastContact = state[4]?.intValue() ?: 0
                location.lat = state[5] ?: 0f
                location.lon = state[6] ?: 0f
                baroAltitude = state[7]?.intValue() ?: 0
                onGround = state[8]?.booleanValue() ?: false
                velocity = state[9]?.floatValue() ?: 0f
                trueTrack = state[10]?.floatValue() ?: 0f
                verticalRate = state[11]?.floatValue() ?: 0f
                sensors = state[12] ? state[12]*.intValue() : null
                geoAltitude = state[13]?.floatValue() ?: 0f
                squawk = state[14]?.trim()
                spi = state[15]?.booleanValue() ?: false
                positionSource = positionSources[state[16].intValue()]
            }
            objResponse.states << stateVector
        }
        return objResponse
    }

}
