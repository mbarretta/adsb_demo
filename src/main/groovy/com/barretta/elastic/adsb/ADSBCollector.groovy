package com.barretta.elastic.adsb

import com.elastic.barretta.clients.ESClient
import groovy.cli.commons.CliBuilder
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsExecutorsPool
import org.elasticsearch.index.query.MatchAllQueryBuilder

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Slf4j
class ADSBCollector {

    static def aircraft = [:] as ConcurrentHashMap
    static ESClient esClient = null

    //todo add setup option that creates the index templates, rollover, etc., plus the aircraft index from the csv
    static void main(String[] args) {
        def cli = new CliBuilder(usage: "adsb_demo")
        cli.once("run once")
        cli.loop("loop forever")
        cli.loopInterval(args: 1, argName: "seconds", "seconds between loops")
        cli.help("print this message")
        def options = cli.parse(args)

        if (options.help) {
            cli.usage()
            System.exit(0)
        }

        initEsClient()

        if (options.loop) {
            def interval = options.loopInterval ? Long.valueOf(options.loopInterval) : 5l
            loopIt(interval)
        } else if (options.once) {
            doIt()
            System.exit(0)
        }
    }

    static void loopIt(long interval) {
        log.info("Let's do this! ...every [$interval] seconds\n")
        log.info("caching all aircraft data")
        getAllAircraft()

        def scheduler = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() - 1)
        scheduler.scheduleAtFixedRate({doIt()} as Runnable, 0, interval, TimeUnit.SECONDS)
//        GParsExecutorsPool.withPool {
//            while (true) {
//                it.execute({ doIt() })
//                log.trace("sleeping [$interval] seconds...")
//                Thread.sleep(interval)
//                log.trace("...done")
//            }
//        }
    }

    static def doIt() {
        log.info("loading all aircraft data")
        getAllAircraft()

        log.info("fetching all states")
        def allStates = OpenSkyNetworkClient.getAllStates()
        log.info(" ...found [${allStates.states.size()}]")

        log.info("fetching all flights")
        def allFlights = OpenSkyNetworkClient.getAllFlights(allStates.time)
        log.info(" ...found [${allFlights.flights.size()}]")

        log.info("joining everything together")
        def esRecords = allStates.states.inject([]) { list, state ->

            /*
            we're doing some field name transformation here as well so that state data has a "state." prefix,
            flight data has "flight.", etc...
            */
            def record = [icao: state.icao]

            //add state data
            def stateFields = state.properties.collectEntries { [("state.${it.key}".toString()): it.value] }
            stateFields.remove("state.icao")
            stateFields.remove("state.class")
            record += stateFields

            //join flight data
            def flight = allFlights.flights.find { it.icao == state.icao }
            if (flight) {
                def flightFields = flight.properties.collectEntries { [("flight.${it.key}".toString()): it.value] }
                flightFields.remove("flight.icao")
                flightFields.remove("flight.class")
                record += flightFields
            }

            //join aircraft data
            if (aircraft.containsKey(state.icao)) {
                record += aircraft.get(state.icao)
            }

            list << record
        }
        log.info(" ...done")

        log.info("bulking into ES")
        esClient.config.index = PropertyManager.instance.properties.indices.opensky
        esClient.bulk([(ESClient.BulkOps.INSERT): esRecords])
        log.info(" ...done")
    }

    static def getAllAircraft() {
        if (aircraft.isEmpty()) {
            esClient.config.index = PropertyManager.instance.properties.indices.aircraft

            esClient.scrollQuery(new MatchAllQueryBuilder(), 5000, 2, 1) { it ->
                def map = it.getSourceAsMap()
                if (map) {
                    aircraft.put(map.remove("icao"), map.collectEntries {
                        [("aircraft.${it.key}".toString()): it.value]
                    })
                }
            }
        }
    }

    static def initEsClient() {
        if (!esClient) {
            def config = PropertyManager.instance.properties.es as ESClient.Config
            esClient = new ESClient(config)
        }
    }
}
