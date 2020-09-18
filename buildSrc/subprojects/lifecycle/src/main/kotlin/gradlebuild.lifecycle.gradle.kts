/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import gradlebuild.basics.BuildEnvironment
import java.time.Duration
import java.util.Timer
import kotlin.concurrent.timerTask

// Lifecycle tasks used to to fan out the build into multiple builds in a CI pipeline.

val ciGroup = "CI Lifecycle"

val compileAllBuild = "compileAllBuild"

val sanityCheck = "sanityCheck"

val quickTest = "quickTest"

val platformTest = "platformTest"

val allVersionsCrossVersionTest = "allVersionsCrossVersionTest"

val allVersionsIntegMultiVersionTest = "allVersionsIntegMultiVersionTest"

val soakTest = "soakTest"


setupTimeoutMonitorOnCI()
setupGlobalState()

tasks.registerDistributionsPromotionTasks()

tasks.registerEarlyFeedbackRootLifecycleTasks()

/**
 * Print all stacktraces of running JVMs on the machine upon timeout. Helps us diagnose deadlock issues.
 */
fun setupTimeoutMonitorOnCI() {
    if (BuildEnvironment.isCiServer) {
        val timer = Timer(true).apply {
            schedule(timerTask {
                exec {
                    commandLine("${System.getProperty("java.home")}/bin/java",
                        rootProject.file("subprojects/internal-integ-testing/src/main/groovy/org/gradle/integtests/fixtures/timeout/JavaProcessStackTracesMonitor.java"))
                }
            }, determineTimeoutMillis())
        }
        gradle.buildFinished {
            timer.cancel()
        }
    }
}

fun determineTimeoutMillis() =
    if (isRequestedTask(compileAllBuild) || isRequestedTask(sanityCheck) || isRequestedTask(quickTest)) {
        Duration.ofMinutes(30).toMillis()
    } else {
        Duration.ofHours(2).plusMinutes(45).toMillis()
    }

fun setupGlobalState() {
    if (needsToUseTestVersionsPartial()) {
        globalProperty("testVersions" to "partial")
    }
    if (needsToUseTestVersionsAll()) {
        globalProperty("testVersions" to "all")
    }
}

fun needsToUseTestVersionsPartial() = isRequestedTask(platformTest)

fun needsToUseTestVersionsAll() = isRequestedTask(allVersionsCrossVersionTest)
    || isRequestedTask(allVersionsIntegMultiVersionTest)
    || isRequestedTask(soakTest)

fun TaskContainer.registerEarlyFeedbackRootLifecycleTasks() {
    register(compileAllBuild) {
        description = "Initialize CI Pipeline by priming the cache before fanning out"
        group = ciGroup
        dependsOn(":base-services:createBuildReceipt")
    }

    register(sanityCheck) {
        description = "Run all basic checks (without tests) - to be run locally and on CI for early feedback"
        group = "verification"
        dependsOn(
            ":docs:checkstyleApi", ":internal-build-reports:allIncubationReportsZip",
            ":architecture-test:checkBinaryCompatibility", ":docs:javadocAll",
            ":architecture-test:test", ":tooling-api:toolingApiShadedJar")
    }
}

/**
 * Task that are called by the (currently separate) promotion build running on CI.
 */
fun TaskContainer.registerDistributionsPromotionTasks() {
    register("packageBuild") {
        description = "Build production distros and smoke test them"
        group = "build"
        dependsOn(":distributions-full:verifyIsProductionBuildEnvironment", ":distributions-full:buildDists",
            ":distributions-integ-tests:forkingIntegTest", ":docs:releaseNotes", ":docs:incubationReport", ":docs:checkDeadInternalLinks")
    }
}

fun globalProperty(pair: Pair<String, Any>) {
    val propertyName = pair.first
    val value = pair.second
    if (hasProperty(propertyName)) {
        val otherValue = property(propertyName)
        if (value.toString() != otherValue.toString()) {
            throw RuntimeException("Attempting to set global property $propertyName to two different values ($value vs $otherValue)")
        }
    }
    extra.set(propertyName, value)
}

fun isRequestedTask(taskName: String) = gradle.startParameter.taskNames.contains(taskName)
    || gradle.startParameter.taskNames.any { it.contains(":$taskName") }
