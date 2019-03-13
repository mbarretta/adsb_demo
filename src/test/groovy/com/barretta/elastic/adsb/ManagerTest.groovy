package com.barretta.elastic.adsb

import spock.lang.Ignore
import spock.lang.Specification

class ManagerTest extends Specification {
//    @Ignore
    def "loopIt"() {
        when:
        def collector = new Manager()
        collector.loopIt(10l)

        then:
        noExceptionThrown()
    }

    def "getAllAircraft works"() {
        expect:
        Manager.getAllAircraft().keySet().size() == 460000
    }

    def "calls rollover"() {
        when:
        Manager.rollover()

        then:
        noExceptionThrown()
    }
}
