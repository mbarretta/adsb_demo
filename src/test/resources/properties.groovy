es {
    url = "http://localhost:9200"
    user = ""
    pass = ""
    index = "opensky"
}
openSky {
    url = "https://opensky-network.org/api"
}
indices {
    aircraft = "aircraft"
    opensky = "opensky"
}
rollover {
    enabled = true
    max_docs = 40000000
    max_size_gb = 20
}
