package com.barretta.elastic.adsb

import spock.lang.Specification

class ADSBCollectorTest extends Specification {
    def "DoIt"() {
        when:
        new ADSBCollector().doIt()

        then:
        noExceptionThrown()
    }
}
