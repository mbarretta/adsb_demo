package com.barretta.elastic.opensky

import groovy.util.logging.Slf4j

@Singleton(strict = false)
@Slf4j
class PropertyManager {
    def properties = [:]

    private PropertyManager() {
        def config = new File(Manager.configFile).toURI().toURL()
        try {
            log.info("loading config: [${config.getPath()}]")
            properties = new ConfigSlurper().parse(config)
        } catch (e) {
            log.error("unable to load properties from [${config.path}]")
        }
    }
}
