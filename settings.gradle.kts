pluginManagement {
  repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
    maven { url = uri("https://repo1.maven.org/maven2/") } 
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://repo1.maven.org/maven2/") }
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots") }
    maven { url = uri("https://jcenter.bintray.com/") } 
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    google()
    maven { url = uri("https://jcenter.bintray.com/") } 
    maven { url = uri("https://repo1.maven.org/maven2/") } 
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://repo1.maven.org/maven2/") }  

  }
}

rootProject.name = "EUIA"

include(":app")