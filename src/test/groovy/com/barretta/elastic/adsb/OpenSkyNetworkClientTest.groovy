package com.barretta.elastic.adsb

import spock.lang.Specification

class OpenSkyNetworkClientTest extends Specification {

    def "GetAllStates"() {
        setup:
        def response = OpenSkyNetworkClient.getAllStates()

        expect:
        response != null
        response.states.size() > 0
    }
}
