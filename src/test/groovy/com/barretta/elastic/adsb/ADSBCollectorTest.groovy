package com.barretta.elastic.adsb

import spock.lang.Ignore
import spock.lang.Specification

class ADSBCollectorTest extends Specification {
    def "DoIt"() {
        when:
        def collector = new ADSBCollector.Collector()
        collector.run()

        then:
        noExceptionThrown()
    }

    @Ignore
    def "loopIt"() {
        when:
        def collector = new ADSBCollector()
        collector.loopIt(10000l)

        then:
        noExceptionThrown()
    }

    def "getAllAircraft works"() {
        expect:
        ADSBCollector.getAllAircraft().keySet().size() == 460000
    }

    def "calls rollover"() {
        expect:
        new ADSBCollector.Rollover().run()
    }
}
