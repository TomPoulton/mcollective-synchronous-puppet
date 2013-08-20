import java.io.InputStream
import java.util.concurrent.TimeoutException

def delay = 5000
def reRunTimeout = 90
def updateTimeout = 360
def environment = Job.getVariable("DEPLOYMENT_ENVIRONMENT")
def versionFact = Job.getVariable("VERSION_FACT")
def newVersion = Job.getVariable("VERSION_NUMBER")
def watch = new StopWatch()

RunBash.enable()

println ''
println "Updating all instances using:"
println "Environment => " + environment
println "Fact        => " + versionFact
println "Version     => " + newVersion
println ''

println 'Finding deployment instances...'
println ''

def findCmd = String.format("mco find -W \"environment=%s %s=/.*/\"", environment, versionFact)
def findResult = findCmd.bash()
def deploymentInstances = ResultParser.toList(findResult)

if (deploymentInstances.size() == 0) {
    throw new RuntimeException("No instances found matching search: " + findCmd)
}

println String.format("Triggering Puppet run on %s instance(s)...", deploymentInstances.size())
println deploymentInstances
println ''

def runPuppetCmd = String.format("mco puppet runonce -W \"environment=%s %s=/.*/\"", environment, versionFact)
def runPuppetResult = runPuppetCmd.bash()
def failedRuns = ResultParser.findFailedBoxes(runPuppetResult)

println String.format("Re-trying %s failed instance(s)...", failedRuns.size())
println failedRuns
println ''

watch.start()
while (failedRuns.size() > 0 && watch.elapsedSecs() < reRunTimeout) {

    Object.sleep(delay)

    for (instance in failedRuns) {

        println String.format("Trying %s again...", instance)

        runPuppetCmd = String.format("mco puppet runonce -W \"hostname=%s\"", instance)
        runPuppetResult = runPuppetCmd.bash()

        if (ResultParser.boxPassed(runPuppetResult)) {
            println String.format("    %s passed", instance)
            failedRuns = failedRuns.minus(instance)
        }
    }
}
if (failedRuns.size() > 0) {
    throw new TimeoutException(String.format("Triggering Puppet runs timed out after %s seconds", watch.elapsedSecs()))
}

println ''
println 'Waiting for Puppet to finish on all instances...'
println ''

def updatedInstances = new ArrayList()

watch.start()
while (updatedInstances.size() != deploymentInstances.size() && watch.elapsedSecs() < updateTimeout) {

    Object.sleep(delay)

    def findUpdatedCmd = String.format("mco find -W \"environment=%s %s=%s\"", environment, versionFact, newVersion)
    def findUpdatedResult = findUpdatedCmd.bash()
    updatedInstances = ResultParser.toList(findUpdatedResult)
}
if (updatedInstances.size() < deploymentInstances.size()) {
    println String.format("Error: Only %s instance(s) out of %s updated:", updatedInstances.size(), deploymentInstances.size())
    println updatedInstances
    throw new TimeoutException(String.format("Waiting for instances to update timed out after %s seconds", watch.elapsedSecs()))
}

println String.format("%s instance(s) updated to version %s", updatedInstances.size(), newVersion)
println updatedInstances
println ''

class ResultParser {

    static def toList(lines) {
        def list = lines.readLines()
        return list
    }

    static def findFailedBoxes(result) {
        def failedBoxes = new ArrayList()

        def lines = toList(result)
        for (line in lines) {
            if (line.contains("Request Aborted")) {
                failedBoxes.add(line.split(" ")[0])
            }
        }
        return failedBoxes as List
    }

    static def boxPassed(result) {
        def failureList = findFailedBoxes(result)
        return (failureList.size() == 0)
    }
}

class Job {

    static def getVariable(String key) {
        def config = new HashMap()
        def thr = Thread.currentThread()
        def build = thr?.executable
        def envVarsMap = build.parent.builds[0].properties.get("envVars")
        config.putAll(envVarsMap)
        return config.get(key)
    }
}

class RunBash {

    static def enable() {
        GString.metaClass.bash = {-> bash(delegate) }
        String.metaClass.bash = {-> bash(delegate) }
    }

    static def bash(cmd) {

        cmd = cmd as String

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        pb.redirectErrorStream(true);
        Process shell = pb.start();
        shell.getOutputStream().close();
        InputStream shellIn = shell.getInputStream();

        def cmdoutput = shellIn.text
        int shellExitStatus = shell.waitFor();

        try {
            shellIn.close();
            pb = null;
            shell = null;
        } catch (IOException ignoreMe) {}

        return cmdoutput
    }
}

class StopWatch {

    def startTime = 0

    def start() {
        // Following line reads as: Calendar.instance.date.time
        startTime = Calendar.instance.time.time
    }

    def elapsedMillis() {
        def elapsed = (Calendar.instance.time.time - startTime)
        return elapsed
    }

    def elapsedSecs() {
        def elapsed = elapsedMillis()
        return Math.round(elapsed / 1000)
    }
}
