package com.barretta.elastic.opensky

import spock.lang.Specification

class CollectorRunnableTest extends Specification {
    def "Run"() {
        when:
        new CollectorRunnable().run()

        then:
        noExceptionThrown()
    }
}
