package com.barretta.elastic.opensky

import spock.lang.Specification

class OpenSkyNetworkClientTest extends Specification {

    def "GetAllStates"() {
        setup:
        def response = OpenSkyNetworkClient.getAllStates()

        expect:
        response != null
        response.states.size() > 0
    }

    def "getAllFlights"() {
        setup:
        def response = OpenSkyNetworkClient.getAllFlights(1548855999)

        expect:
        response != null
        response.flights.size() > 0
    }
}
