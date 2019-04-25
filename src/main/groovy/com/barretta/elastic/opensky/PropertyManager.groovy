package com.barretta.elastic.opensky

@Singleton(strict = false)
class PropertyManager {
    def properties = [:]

    private PropertyManager() {
        properties = new ConfigSlurper().parse(GroovyClassLoader.getSystemResource("properties.groovy"))
    }
}
