plugins {
    flyway
}

dependencies {
    // 核心依赖
    api(projects.api)

    // API
    api(libs.bundles.configurate.core) {
        isTransitive = false
    }
    api(libs.bundles.configurate.yaml) {
        isTransitive = false
    }
    annotationProcessor(libs.configurate.interfaces.ap)
    api(libs.colorparser.common) {
        exclude("net.kyori")
    }
    api(libs.threadutil.common)

    // Database dependencies - Core
    api(libs.hikaricp)
    compileOnly(libs.bundles.flyway)
    flywayDriver(libs.h2)

    // Database dependencies - JDBC drivers
    compileOnly(libs.bundles.jdbcdrivers)

    // 测试依赖（main 中 flyway 为 compileOnly、JDBC 驱动为 compileOnly/flywayDriver，
    // 测试运行时需要真实类；hikaricp 已由 api 配置传递到测试类路径）
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testImplementation(libs.annotations)
    testImplementation(libs.bundles.flyway)
    // DatabaseType 枚举类初始化引用全部四个驱动类，故 mysql/mariadb 也需在测试类路径
    testImplementation(libs.bundles.jdbcdrivers)
    testRuntimeOnly(libs.slf4j)
}

flyway {
    url = provider {
        "jdbc:h2:${project.layout.buildDirectory.get()}/generated/flyway/db;AUTO_SERVER=TRUE;MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;IGNORECASE=TRUE"
    }
    user = "sa"
    password = ""
    schemas = listOf("PUBLIC")
    placeholders = mapOf(
        "tablePrefix" to "",
    )
    validateMigrationNaming = true
    baselineOnMigrate = true
    cleanDisabled = false
    enableRdbmsSpecificMigrations = true
    rdbmsLocations = listOf("h2", "sqlite", "mysql", "mariadb")
    locations = listOf(
        "filesystem:${project.layout.projectDirectory}/src/main/resources/db/migration/",
        "classpath:${mainPackage.replace(".", "/")}/database/migration/migrations"
    )
}
