package com.barretta.elastic.adsb

import com.elastic.barretta.clients.ESClient
import groovy.util.logging.Slf4j

@Slf4j
class ADSBCollector {
    private static def properties = new ConfigSlurper().parse(GroovyClassLoader.getSystemResource("properties.groovy"))

    static void main(String[] args) {
        log.info("using properties:\n" + properties)
        if (args && args[0] == "loop") {
            loopIt()
        } else {
            doIt()
        }
        System.exit(0)
    }

    static void loopIt() {
        while (true) {
            doIt()
            Thread.sleep(5000)
        }
    }

    static void doIt() {
        log.info("fetching all states")
        def allStates = OpenSkyNetworkClient.getAllStates()
        log.info(" ...found [${allStates.states.size()}]")

        def client = new ESClient(properties.esClient as ESClient.Config)
        client.bulk([(ESClient.BulkOps.INSERT): allStates.states.collect {
            it.properties.remove("class"); it.properties
        }])
        log.info("done")
    }
}
