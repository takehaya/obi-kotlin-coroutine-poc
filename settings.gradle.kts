rootProject.name = "obi-kotlin-coroutine-poc"

include(":agent", ":frontend", ":backend", ":jfront")
project(":frontend").projectDir = file("demo/frontend")
project(":backend").projectDir = file("demo/backend")
project(":jfront").projectDir = file("demo/jfront")
