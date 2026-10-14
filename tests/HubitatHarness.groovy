import groovy.json.JsonOutput

class FakeApp {
    String id = '42'
    Map settings
    void removeSetting(String name) { settings.remove(name) }
    void updateSetting(String name, Map value) { settings[name] = value.type == 'bool' ? value.value.toString() == 'true' : value.value }
}
class FakeDevice {
    String deviceNetworkId = 'test'
    String displayName = 'Test device'
    String name = 'Test device'
    boolean component = false
    Map data = [:]
    Map settings = [:]
    Map attributes = [:]
    List events = []
    List calls = []
    boolean active = false
    long cg = 0L
    long ag = 0L
    Object currentValue(String key) { attributes[key] }
    String getDataValue(String key) { data[key] }
    void updateDataValue(String key, String value) { data[key] = value }
    void updateSetting(String name, Map value) { settings[name] = value.type == 'bool' ? value.value.toString() == 'true' : value.value }
    void applyAttributes(Map values) { attributes.putAll(values) }
    void applySnapshot(Map values) { attributes.putAll(values) }
    void deleteCurrentState(String name) { attributes.remove(name) }
    void startConnection(long gen) { active = true; ag = gen; cg++; calls << ['start', gen] }
    void stopConnection() { active = false; cg++; calls << ['stop'] }
    boolean connectionActive() { active }
    long connectionGeneration() { cg }
    long accountGeneration() { ag }
    void openSocket(String url, long gen, long cgen) { calls << ['open', url, gen, cgen] }
    void connectionFailed(String reason) { calls << ['failed', reason] }
}
class FakeParent {
    List messages = []
    void postMessage(Map msg) { messages << msg }
}
class FakeSocket {
    List sent = []
    List urls = []
    int closed = 0
    void connect(String url) { urls << url }
    void sendMessage(String frame) { sent << frame }
    void close() { closed++ }
}
class FakeResponse {
    int status
    Object body
    Map headers = [:]
    boolean errorBodyOnly = false
    Object errorDataBody
    int getStatus() { status }
    String getData() { if (errorBodyOnly) throw new Exception('Body available through getErrorData'); body instanceof String ? body : JsonOutput.toJson(body) }
    String getErrorData() { Object value = errorDataBody != null ? errorDataBody : body; value instanceof String ? value : JsonOutput.toJson(value) }
    Map getHeaders() { headers }
}
// Models the header-wrapper behavior seen on the physical hub: its findAll
// returns entries as a list. Cookie processing must not depend on .values().
class FakeHubitatHeaders extends LinkedHashMap {
    Object findAll(Closure predicate) {
        List found = []
        each { k, v -> if (predicate(k, v)) found << [key: k, value: v] }
        found
    }
}
class FakeBadCookie {
    String toString() { throw new Exception('Malformed cookie metadata') }
}
class FakeNativeResponse {
    int status
    List headers = []
    int getStatus() { status }
    Object getAllHeaders() { headers as Object[] }
    Object getHeaders() { headers.collectEntries { [(it.name): it] } }
}
class FakeAtomicState {
    Map durable
    List events
    Object propertyMissing(String name) { durable[name] }
    void propertyMissing(String name, Object value) {
        durable[name] = value == null ? null : new groovy.json.JsonSlurper().parseText(JsonOutput.toJson(value))
        events << [name: name, value: durable[name]]
    }
}
abstract class HubitatScript extends Script {
    Map state = [:]
    Map durable = [:]
    List durabilityEvents = []
    Object atomicState = new FakeAtomicState(durable: durable, events: durabilityEvents)
    Map settings = [:]
    FakeApp app = new FakeApp(settings: settings)
    Map location = [temperatureScale: 'F', timeZone: TimeZone.getTimeZone('UTC'), hub: [firmwareVersionString: 'test']]
    FakeDevice device = new FakeDevice(settings: settings)
    Object parent = new FakeParent()
    Map interfaces = [webSocket: new FakeSocket()]
    Map children = [:]
    List jobs = []
    List requests = []
    List subscriptions = []
    List metadataCalls = []
    List logs = []
    Object log = [debug: { Object s -> logs << s }, info: { Object s -> logs << s }, warn: { Object s -> logs << s }, error: { Object s -> logs << s }]
    long clock = 1792000000000L
    long now() { clock }
    String getTemperatureScale() { location.temperatureScale }
    String getDataValue(String name) { device.getDataValue(name) }
    void sendEvent(Map event) { device.attributes[event.name] = event.value; device.events << event }
    List getChildDevices() { children.values().toList() }
    FakeDevice getChildDevice(String dni) { children[dni] }
    FakeDevice addChildDevice(String namespace, String type, String dni, Map options) {
        assert namespace == 'resideoHubitat'
        assert !children[dni]
        def d = new FakeDevice(deviceNetworkId: dni, displayName: options.label, name: type, component: options.isComponent == true)
        children[dni] = d
        d
    }
    void deleteChildDevice(String dni) { children.remove(dni) }
    void subscribe(Object source, String event, String method) { subscriptions << [source, event, method] }
    void unsubscribe() { subscriptions.clear() }
    void unschedule(String handler = null) { if (handler == null) jobs.clear(); else jobs.removeAll { it.handler == handler } }
    void runIn(Number delay, String handler, Map options = [:]) { scheduleJob(delay.longValue() * 1000L, handler, options) }
    void runInMillis(Number delay, String handler, Map options = [:]) { scheduleJob(delay.longValue(), handler, options) }
    void scheduleJob(long delay, String handler, Map options) {
        if (options.overwrite != false) jobs.removeAll { it.handler == handler }
        jobs << [at: clock + delay, handler: handler, data: options.data]
    }
    void advance(long millis) {
        long target = clock + millis
        int count = 0
        while (true) {
            Map job = jobs.findAll { it.at <= target }.sort { it.at }.find()
            if (!job) break
            assert ++count < 10000 : 'Runaway scheduler'
            jobs.remove(job); clock = job.at
            if (job.data != null) this.invokeMethod(job.handler, job.data)
            else this.invokeMethod(job.handler, null)
        }
        clock = target
    }
    void asynchttpGet(String callback, Map params, Map data) { http('GET', callback, params, data) }
    void asynchttpPost(String callback, Map params, Map data) { http('POST', callback, params, data) }
    void asynchttpPut(String callback, Map params, Map data) { http('PUT', callback, params, data) }
    void httpGet(Map params, Closure callback) { requests << [verb: 'GET', params: params, nativeCallback: callback, data: state.authRequest] }
    void httpPost(Map params, Closure callback) { requests << [verb: 'POST', params: params, nativeCallback: callback, data: state.authRequest] }
    void http(String verb, String callback, Map params, Map data) {
        requests << [verb: verb, callback: callback, params: params, data: data,
            durableRefreshing: durable.refreshing, durableRefreshToken: durable.tokens?.refresh_token]
    }
    void respond(Map request, int status, Object body, Map headers = [:]) {
        if (request.nativeCallback) {
            List entries = []
            headers.each { k, v -> (v instanceof Collection ? v : [v]).each { entries << [name: k, value: it] } }
            request.nativeCallback(new FakeNativeResponse(status: status, headers: entries))
            return
        }
        invokeMethod(request.callback, [new FakeResponse(status: status, body: body, headers: headers), request.data] as Object[])
    }
    void definition(Map options, Closure body = null) { metadataCalls << ['definition', options]; if (body) dsl(body) }
    void metadata(Closure c) { dsl(c) }
    void preferences(Closure c) { dsl(c) }
    void section(String title, Closure c) { dsl(c) }
    Object dynamicPage(Map props, Closure c) { dsl(c); props }
    void dsl(Closure c) { c.delegate = this; c.resolveStrategy = Closure.DELEGATE_FIRST; c.call() }
    Object methodMissing(String name, Object args) {
        if (name in ['capability', 'attribute', 'command', 'page', 'paragraph', 'input', 'href']) { metadataCalls << [name, args.toList()]; return null }
        throw new MissingMethodException(name, this.class, args as Object[])
    }
}
