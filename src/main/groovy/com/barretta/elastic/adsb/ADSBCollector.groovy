package com.barretta.elastic.adsb

import com.elastic.barretta.clients.ESClient
import groovy.util.logging.Slf4j

@Slf4j
class ADSBCollector {
    private static def properties = new ConfigSlurper().parse(GroovyClassLoader.getSystemResource("properties.groovy"))

    static void main(String[] args) {
        log.info("using properties:\n" + properties)
        if (args && args[0] == "loop") {
            loopIt(args)
        } else {
            doIt()
        }
        System.exit(0)
    }

    static void loopIt(args) {
        def rate = 10000l
        if (args.length > 1 && args[1] && !args[1].isEmpty()) {
            rate = Long.parseLong(args[1])
        }

        while (true) {
            doIt()
            Thread.sleep(rate)
        }
    }

    static void doIt() {
        log.info("fetching all states")
        def allStates = OpenSkyNetworkClient.getAllStates()
        log.info(" ...found [${allStates.states.size()}]")

        log.info("fetching all flights")
        def allFlights = OpenSkyNetworkClient.getAllFlights(allStates.time)
        log.info(" ...found [${allFlights.flights.size()}]")

        log.info("joining states and flights")
        def esRecord = allStates.states.inject([]) { list, state ->
            def record = state.properties

            def flight = allFlights.flights.find { it.icao == state.icao }
            if (flight) {
                record += flight.properties
            }
            list << record
        }
        log.info(" ...done")

        log.info("loading ES")
        def client = new ESClient(properties.esClient as ESClient.Config)
        client.bulk([(ESClient.BulkOps.INSERT): esRecord])
        log.info(" ...done")
    }
}
