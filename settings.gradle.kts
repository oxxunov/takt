pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "Takt"

// Дерево зависимостей строго однонаправленное:
//   :app  ->  :engine  ->  :core
// Обратных стрелок нет. :core не знает про Android и потому тестируется на JVM.
include(":core", ":engine", ":app")
