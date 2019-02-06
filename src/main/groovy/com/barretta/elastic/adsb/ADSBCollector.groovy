package com.barretta.elastic.adsb

import com.elastic.barretta.clients.ESClient
import groovy.cli.commons.CliBuilder
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsExecutorsPool
import org.elasticsearch.index.query.MatchAllQueryBuilder

import java.util.concurrent.ConcurrentHashMap

@Slf4j
class ADSBCollector {

    //todo add setup option that creates the index templates, rollover, etc., plus the aircraft index from the csv
    static void main(String[] args) {
        def cli = new CliBuilder(usage: "adsb_demo")
        cli.once("run once")
        cli.loop("loop forever")
        cli.loopInterval(args: 1, argName: "seconds","seconds between loops")
        cli.help("print this message")
        def options = cli.parse(args)

        if (options.help) {
            cli.usage()
            System.exit(0)
        }

        if (options.loop) {
            def interval = options.loopInterval ? Integer.valueOf(options.loopInterval) : 5
            loopIt(interval * 1000)
        } else if (options.once) {
            doIt()
        }

        System.exit(0)
    }

    static void loopIt(int interval) {
        log.info("Let's do this! Looping every [$interval] milliseconds")
        log.info("caching all aircraft data")
        getAllAircraft()

        GParsExecutorsPool.withPool {
            while (true) {
                it << { doIt() } as Runnable
                log.trace("sleeping...")
                Thread.sleep(interval)
                log.trace("...done")
            }
        }
    }

    static def doIt() {
        log.info("fetching all states")
        def allStates = OpenSkyNetworkClient.getAllStates()
        log.info(" ...found [${allStates.states.size()}]")

        log.info("fetching all flights")
        def allFlights = OpenSkyNetworkClient.getAllFlights(allStates.time)
        log.info(" ...found [${allFlights.flights.size()}]")

        log.info("loading all aircraft data")
        def allAircraft = getAllAircraft()

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
            if (allAircraft.containsKey(state.icao)) {
                record += allAircraft.get(state.icao)
            }

            list << record
        }
        log.info(" ...done")

        log.info("bulking into ES")
        def esConfig = PropertyManager.instance.properties.es as ESClient.Config
        esConfig.index = PropertyManager.instance.properties.indices.opensky
        def client = new ESClient(esConfig)
        client.bulk([(ESClient.BulkOps.INSERT): esRecords])
        log.info(" ...done")
    }

    @Memoized
    static def getAllAircraft() {
        def esConfig = PropertyManager.instance.properties.es as ESClient.Config
        esConfig.index = PropertyManager.instance.properties.indices.aircraft
        def es = new ESClient(esConfig)

        def aircraft = [:] as ConcurrentHashMap
        es.scrollQuery(new MatchAllQueryBuilder(), 10000, 2, 1) { it ->
            def map = it.getSourceAsMap()
            if (map) {
                aircraft.put(map.remove("icao"), map.collectEntries { [("aircraft.${it.key}".toString()): it.value] })
            }
        }
        return aircraft
    }
}
