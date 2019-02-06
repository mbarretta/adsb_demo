package com.barretta.elastic.adsb

import spock.lang.Ignore
import spock.lang.Specification

class ADSBCollectorTest extends Specification {
    def "DoIt"() {
        when:
        new ADSBCollector().doIt()

        then:
        noExceptionThrown()
    }

//    @Ignore
    def "loopIt"() {
        when:
        new ADSBCollector().loopIt(10000l)

        then:
        noExceptionThrown()
    }

    def "getAllAircraft works"() {
        expect:
        ADSBCollector.allAircraft.keySet().size() == 460000
    }
}
