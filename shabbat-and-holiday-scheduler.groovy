import groovy.transform.Field
import java.math.BigDecimal
import java.util.Date
import java.util.Calendar
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.Random
import java.util.TimeZone
import java.text.SimpleDateFormat

@Field static final String SEASONAL = "__seasonal__"
@Field static final String MANUAL_EARLY = "__manual_early__"

@Field static final String PESACH = "Pesach"
@Field static final String SHAVUOT = "Shavuot"
@Field static final String SUKKOT = "Sukkot"
@Field static final String YOM_KIPPUR = "Yom Kippur"
@Field static final String SHMINI_ATZERET = "Shmini Atzeret"

@Field static final String HOLIDAY = "holiday"
@Field static final String CANDLES = "candles"
@Field static final String HAVDALAH = "havdalah"

@Field static final String HAVDALAH_FIRE = "Fire"
@Field static final String HAVDALAH_NO_FIRE = "No Fire"

@Field static final Random random = new Random()
@Field static final Map<String, List> eventLists = new ConcurrentHashMap<>()

@Field static final Set fetchInProgress = new HashSet<>()
@Field static final Set needsRefresh = new HashSet<>()

metadata {
 	definition (name: "Shabbat and Holiday Scheduler", namespace: "shabbatmode", author: "Daniel Segall") {
        capability "Actuator"
        capability "PushableButton"
        capability "Configuration"
        capability "Initialize"
        capability "Refresh"
        attribute "regularTime","number"
        attribute "earlyTime","number"
        attribute "plagTime","number"
        attribute "activeTime", "number"
        attribute "times", "string"
        attribute "activeType", "enum", ["Regular", "Plag", "Early"]
        attribute "havdalah", "enum", ["None", "Fire", "No Fire"]
        attribute "specialHoliday", "enum", ["", PESACH, SHAVUOT, SUKKOT, YOM_KIPPUR]
        attribute "candleLightingSpeakText", "string"
        command "regular"
        command "plag"
        command "early"
        command "havdalahMade"
        command "unscheduleAllEvents"
        command "testEvent", [[name:"Type*", type:"ENUM", constraints: [CANDLES,HAVDALAH,HOLIDAY]], [name:"Delay*", type:"NUMBER", description:"How long from, in seconds, from when the testEvent button is pushed to schedule this event"], [name:"Holiday", type:"ENUM", constraints:[PESACH, SHAVUOT, SUKKOT, YOM_KIPPUR, SHMINI_ATZERET]]]
     }
 }

preferences
{
    section
    {
        input name: "candlelightingoffset", type: "number", title: "Candle Lighting", required: true, defaultValue: 18, description: "Zman candle lighting, minutes before sunset"
        input name: "plagoffset", type: "number", title: "Plag Offset", required: true, defaultValue: 60, description: "Plag candle lighting, minutes before Zman candle lighting"
        input name: "earlyShabbatTime", type: "time", title: "Time for Early Shabbat", required: true, defaultValue: "19:00", description: "The time for \"Early\" Shabbat which does not change each week"
        input name: "startMode", type: "enum", title: "Mode at Shabbat Start", required:true, options: getModeOptions(), defaultValue: "Shabbat"
        input name: "endMode", type: "enum", title: "Mode at Shabbat End", required: true, options: getModeOptions(), defaultValue: "Home"
        input name: "notWhen", type: "enum", title: "Don't go into Shabbat mode if mode is...", options: getModeOptions(), required: false, defaultValue: "Away"
        input name: "ignoreHavdalahOnFireAfter", type: "number", title: "Assume Havdalah has already been made after this much time", required: false, defaultValue: 60, description: "Enter minutes, or 0 to disable this feature"
        input name: "preferEarly", type: "bool", title: "Prefer Early Shabbat", description: "If turned on and the time is switched to Zman, it will automatically revert to the previously selected early type after the next Shabbat ends", defaultValue: true
        input name: "makerUrl", type: "string", title: "Maker API base URL", required: false, description: "The base URL for the maker API, up to and including 'devices/'"
        input name: "accessToken", type: "string", title: "Maker API access token", required: false, description: "Access token for the maker API"
        input name: "debugLogging", type: "bool", title: "Debug Logging", defaultValue: true
        input name: "refreshOnSave", type: "bool", title: "Refresh on Save", required: false, description: "If you don't know what this does, leave it turned ON", defaultValue: true
    }
}

List<String> getModeOptions() {
    List<String> options = new ArrayList<>()
    for (Object mode : location.getModes())
        options.add(mode.toString())
    
    return options
}

def installed() {
    fullReset()
    runIn(300, debugOff)
}

def debugOff() {
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

def createStateMap() {
    if (state.savedTypes == null)
         state.savedTypes = new HashMap()
}
 
 def initialize() {
     doUnschedule()
     createStateMap()
     device.deleteCurrentState("candleLightingSpeakText")
     
     schedulePendingEvent()
    fetchSchedule()
 }

def scheduleFetchTask() {     
    String scheduleStr = String.format("%d %d %d 1 * ?", random.nextInt(60), random.nextInt(59) + 1, random.nextInt(6))
     if (debugLogging)
         log.debug "schedule fetcher cron string is " + scheduleStr
     
     schedule(scheduleStr, fetchSchedule, [overwrite: true])
 }

def refresh() {
    fullReset()
}

def unscheduleAllEvents() {
    doUnschedule()
    List eventList = eventLists.get(device.id)
    if (eventList != null)
        eventList.clear()
    
    if (debugLogging) {
        log.debug "unscheduleAllEvents event list is now ${eventList}"
    }
}

def fullReset() {
    log.info "fullReset"
    device.deleteCurrentState("specialHoliday")
    device.deleteCurrentState("havdalah")
    state.initializing = true
    state.specialHoliday = null
    initialize()
}

def configure() {
    initialize()
}

def updated() {
    if (refreshOnSave)
        initialize()
 }

def uninstalled() {
    doUnschedule()
    eventLists.remove(device.id)
}

def doUnschedule() {
    unschedule(fetchSchedule)
    unschedule(updateSpeakText)
    unschedule(shabbatStart)
    unschedule(shabbatEnd)
    unschedule(havdalahMade)
}

def testEvent(String eventType, BigDecimal delay, String holiday) {
    log.info "Received test request: eventType=${eventType}, delay=${delay}, holiday=${holiday}"
    List eventList = eventLists.get(device.id)
    if (eventList != null && state.nextEventTest) {
        for (int i = 0; i < eventList.size(); i++) {
            def event = eventList.get(i)
            if (!event.isTest) {
                eventList.add(i, [isTest: true, type: eventType, name: (eventType == HOLIDAY ? "Erev " + holiday : eventType), when: new Date(now() + (1000 * delay.longValue()))])
                if (debugLogging) log.debug "eventList is ${eventList}"
                return
            }
        }
    }
    else {
        doUnschedule()
        fetchSchedule(eventType, delay.intValue(), holiday)
    }
}

def fetchSchedule(String testEventType=null, int testEventDelay=-1, String testHoliday=null) {
    if (debugLogging) {
        log.debug "fetchSchedule testEventType=${testEventType}"
    }
    
    synchronized (fetchInProgress) {
        if (!fetchInProgress.add(device.id)) {
            needsRefresh.add(device.id)
            if (debugLogging)
                log.debug "Deferring fetch because fetch already in progress"
            
            return
        }
    }
    
    state.expectEmptyList = false
    Calendar cal = Calendar.getInstance()
    int month = cal.get(Calendar.MONTH)
    final boolean detectChanuka = month == Calendar.NOVEMBER || month == Calendar.DECEMBER || month == Calendar.JANUARY
    TimeZone tz = location.timeZone
    BigDecimal latitude = location.latitude
    BigDecimal longitude = location.longitude
    String zip = location.zipCode
    String geo = (tz != null && latitude != null && longitude != null ? "pos" : zip != null ? "zip" : "none")
    String locationParams = ""
    
    switch (geo) {
        case "pos":
            locationParams = String.format("latitude=%s&longitude=%s&tzid=%s", latitude.toString(), longitude.toString(), tz.getID())
            break
        
        case "zip":
            locationParams = "zip=" + zip
            break
        
        default:
            log.error "Could not determine location, no zip code or latitude/longitude/time zone is configured on the Hub"
            return
    }
    
    String url = String.format("https://www.hebcal.com/hebcal/?v=1&cfg=json&maj=%s&min=off&mod=off&nx=off&year=now&month=%d&ss=off&mf=off&c=on&geo=%s&%s&M=on&s=off&b=%d", (detectChanuka ? "off" : "on"), month + 1, geo, locationParams, candlelightingoffset)
    
    if (debugLogging)
        log.debug "url is " + url
    
    Map data = [test: testEventType, delay: testEventDelay, holiday: testHoliday]
    httpGet(url, {response -> scheduleUpdater(response, data)})
}

def scheduleUpdater(response, data) {
    try {
        if (response.getStatus() != 200) {
            log.error response.getErrorData()
            state.expectEmptyList = true
            return
        }
    
        result = response.getData()
        if (debugLogging)
            log.debug "Response: " + result.items
    
        if (result.items.length == 0) {
            log.error "Schedule query returned no data"
            state.expectEmptyList = true
            return
        }
    
        List eventList = eventLists.get(device.id)
        if (eventList == null) {
            eventList = new ArrayList()
            eventLists.put(device.id, eventList)
        }
        else {
            eventList.clear()
        }
    
        for (item in result.items) {
            if (item.category == CANDLES || item.category == HAVDALAH)
                eventList.add([type: item.category, when: toDateTime(item.date)])
            else if (item.category == HOLIDAY && (item.title.contains("Erev") || item.title == SHMINI_ATZERET))
                eventList.add([type: item.category, name: item.title, when: toDateTime(item.date)])
        }
        
        if (data["test"] != null) {
            String eventType = data["test"]
            String holiday = data["holiday"]
            
            if (debugLogging) {
                log.debug "Adding test event ${eventType} ${holiday} ${data}"
            }
            
            eventList.add(0, [isTest: true, type: eventType, name: (eventType == HOLIDAY ? "Erev " + holiday : eventType), when: new Date(now() + (1000 * data["delay"]))])
        }
    
        if (debugLogging) {
            log.debug "Events created: ${eventList}"
        }
    
        if (eventList == null || eventList.isEmpty()) {
            state.expectEmptyList = true
        }
        else {
            scheduleNextShabbatEvent()
        }
    
        if (debugLogging)
            log.debug "Schedule fetch complete"
        }
    finally {
        boolean rerun = false
        synchronized (fetchInProgress) {
            fetchInProgress.remove(device.id)
            rerun = needsRefresh.remove(device.id)
        }
        
        if (rerun) {
            if (debugLogging)
                log.debug "Needs to re-execute fetch"
            
            runIn(1, fetchSchedule)
        }
        else
            scheduleFetchTask()
    }
}

def scheduleNextShabbatEvent() {
    state.nextEventTest=false
    long currentTime = now()
    List eventList = eventLists.get(device.id)
    
    if ((eventList == null || eventList.isEmpty())) {
        if (!state.expectEmptyList) {
            // Re-fetch
            fetchSchedule()
        }
        else {
            state.specialHoliday = null
            state.nextEventType = null
            state.nextEventTime = null
        }
        
        return
    }
    
    while (!eventList.isEmpty()) {
        Map data = eventList.remove(0)
        if (debugLogging)
            log.debug "Current event data is " + data
        
        if (data.when.getTime() < currentTime && !data["isTest"]) {
            if (debugLogging)
                log.debug "Skipping current event data because it is in the past"
            
            continue
        }
        
        String currentEventType = data.type
        if (debugLogging)
            log.debug "Current event type is " + currentEventType
        
        if (currentEventType == "holiday") {
            saveCurrentType(HOLIDAY, true)
            regular()
            
            if (data.name.contains(SHAVUOT)) {
                state.specialHoliday = SHAVUOT
            }
            else if (data.name.contains(PESACH)) {
                state.specialHoliday = PESACH
            }
            else if (data.name.contains(SUKKOT)) {
                state.specialHoliday = SUKKOT
            }
            else if (data.name.contains(YOM_KIPPUR)) {
                state.specialHoliday = YOM_KIPPUR
            }
            else if (data.name == SHMINI_ATZERET) {
                state.specialHoliday = null
            }
        }
        else {
            String type = data.type
            // This code was for an old HebCal bug where they didn't report Havdalah events on Chanuka.  That seems to be resolved
            /*Calendar cal = Calendar.getInstance()
            cal.setTime(data.when)
            
            int month = cal.get(Calendar.MONTH)
            final boolean detectChanuka = month == Calendar.NOVEMBER || month == Calendar.DECEMBER || month == Calendar.JANUARY
            
            if (detectChanuka && cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY && type == CANDLES) {
                type = HAVDALAH
            }*/
            
            state.nextEventType = type
            state.nextEventTime = data.when
            if (data["isTest"])
                state.nextEventTest = true
            
            schedulePendingEvent()
            break
        }
    }
    
    if (debugLogging) {
        log.debug "scheduleNextShabbatEvent Events remaining: ${eventList}"
    }
    
    if (eventList == null || eventList.isEmpty()) {
        state.expectEmptyList = true
    }
}

def schedulePendingEvent() {
    if (debugLogging) {
        log.debug "schedulePendingEvent, ${state.nextEventType} at ${state.nextEventTime}, initializing=${state.initializing}, isTest=${state.nextEventTest}"
    }
    
    if (state.nextEventType != null && state.nextEventTime != null) {
        Object nextEventTime = state.nextEventTime
        if (!(nextEventTime instanceof Date))
            nextEventTime = toDateTime(nextEventTime.toString())
        
        if (state.nextEventType == CANDLES) {
            setRegularTime(nextEventTime.getTime())
            if (state.initializing) {
                state.initializing = false
                if (preferEarly)
                    plag()
                else
                    regular()
            }
            
            nextEventTime = getActiveTime()
        }
        
        if (debugLogging) {
            log.debug "schedulePendingEvent, nextEventTime is ${nextEventTime}"
        }
        
        if (nextEventTime.getTime() < now()) {
            if (debugLogging)
                log.debug "Pending event is in the past, executing immediately"
            
            if (state.nextEventType == CANDLES) {
                shabbatStart()
            }
            else {
                shabbatEnd()
            }
            
            return
        }
        
        Calendar cal = Calendar.getInstance()
        cal.setTime(nextEventTime)
        
        String scheduleStr = String.format("%d %d %d %d %d ? %d", cal.get(Calendar.SECOND), cal.get(Calendar.MINUTE), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
                
        if (debugLogging) {
            log.debug "schedule cron string is ${scheduleStr}"
            log.debug "next event is ${state.nextEventType} at ${nextEventTime}, holiday=${state.specialHoliday}"
        }
        
        if (state.nextEventType == CANDLES) {
            schedule(scheduleStr, shabbatStart)
            
            if (!state.nextEventTest) {
                Calendar speakTime = Calendar.getInstance()
                speakTime.setTime(nextEventTime)
                speakTime.set(Calendar.SECOND, 0)
                speakTime.set(Calendar.MINUTE, 0)
                speakTime.set(Calendar.HOUR_OF_DAY, 0)
                Map extraData =[:]
                extraData["when"] = nextEventTime
                if (now() >= speakTime.getTimeInMillis()) {
                    updateSpeakText(extraData)
                }
                else {                    
                    String textUpdateSchedule = String.format("%d %d %d %d %d ? %d", 0, 1, 0, cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
                    if (debugLogging) {
                        log.debug "scheduling candle lighting text update for ${textUpdateSchedule}"
                    }
                    
                    schedule(textUpdateSchedule, updateSpeakText, [data: extraData])
                }
            }
        }
        else
            schedule(scheduleStr, shabbatEnd)
    }
    else if (debugLogging) {
        log.debug "No pending event found"
    }
    
    List eventList = eventLists.get(device.id)
    if (debugLogging) {
        log.debug "schedulePendingEvent Events remaining: ${eventList}"
    }
    
    if ((eventList == null || eventList.isEmpty()) && !state.expectEmptyList) {
        // Re-fetch
        fetchSchedule()
    }
}

def updateSpeakText(data) {
    SimpleDateFormat sdf = new SimpleDateFormat("h:mm a")
    String text = String.format("Candle lighting today is at %s", sdf.format((Date)data["when"]))
    sendEvent("name": "candleLightingSpeakText", "value": text)
}

def shabbatStart() {
    if (notWhen != null && location.getMode() != notWhen) {
        if (location.getMode() != startMode) {
            havdalahMade()
            log.info "shabbatStart setting mode to ${startMode}, holiday=${state.specialHoliday}"
            location.setMode(startMode)
            if (state.specialHoliday == null || state.specialHoliday.isEmpty()) {
                device.deleteCurrentState("specialHoliday")
            }
            else {
                sendEvent("name": "specialHoliday", value: state.specialHoliday)
            }
        }
    }
    else {
        if (debugLogging)
            log.debug "Not setting mode to " + startMode + " because mode is " + notWhen
    }
    
    shabbatEventTriggered()
}

def shabbatEnd() {
    String nextSpecialHoliday = state.specialHoliday
    String aish = HAVDALAH_NO_FIRE    
    log.info "shabbatEnd setting mode to " + endMode
        
    if (location.getMode() != endMode) {
        location.setMode(endMode)
    }
        
    if (state.specialHoliday != SUKKOT) {
        nextSpecialHoliday = null
    }
        
    Calendar cal = Calendar.getInstance()
    if (cal.get(Calendar.DAY_OF_WEEK) == 7 || state.specialHoliday == YOM_KIPPUR) {
        log.info "Need havdalah on fire..."
        aish = HAVDALAH_FIRE
        if (ignoreHavdalahOnFireAfter != null && ignoreHavdalahOnFireAfter > 0)
            runIn(ignoreHavdalahOnFireAfter * 60, havdalahMade)
    }
    
    state.specialHoliday = nextSpecialHoliday
    if (nextSpecialHoliday == null) {
        device.deleteCurrentState("specialHoliday")
    }
    else {
        sendEvent("name": "specialHoliday", "value": nextSpecialHoliday)
    }
    
    sendEvent("name": "havdalah", "value": aish)
    device.deleteCurrentState("candleLightingSpeakText")
    
    // Schedule the next event before restoring the previous type to avoid rescheduling the same pending event infinitely
    shabbatEventTriggered()
    
    restorePreviousType(HOLIDAY, true)
    if (preferEarly)
        restorePreviousManualType()
}

def havdalahMade() {
    device.deleteCurrentState("havdalah")
    unschedule(havdalahMade)
}

def shabbatEventTriggered() {
    scheduleNextShabbatEvent()
}

def setRegularTime(long date) {
    sendEvent("name":"regularTime", "value":date)
    def activeType = getActiveType()

    updateActiveTime(activeType, regularTimeOnCalendar(date))
}

def getActiveType() {
    def activeType = device.getDataValue("activeType")
    if (activeType == null)
        activeType = device.currentValue("activeType")
    
    if (activeType == null)
        activeType = "Plag"
    
    return activeType
}

def plag() {
    updateActiveTime("Plag")
}

def regular() {
    updateActiveTime("Regular")
}

def early() {
    updateActiveTime("Early")
}

def saveCurrentType(key, onlyIfNotSet=false) {
    saveType(key, device.currentValue("activeType"), onlyIfNotSet)
}

def saveType(key, type, onlyIfNotSet=false) {
    createStateMap()
    if (!onlyIfNotSet || state.savedTypes[key] == null)
        state.savedTypes[key] = type
}

def restorePreviousType(key, remove=false) {
    def type = getPreviousType(key)
    if (type)
        updateActiveTime(type)
    
    if (remove)
        removePreviousType(key)
}

def restorePreviousManualType() {
    restorePreviousType(MANUAL_EARLY)
}

def getPreviousType(key) {
    createStateMap()
    return state.savedTypes[key]
}

def removePreviousType(key) {
    createStateMap()
    state.savedTypes[key] = null
}

def updateActiveTime(type) {
    Calendar regular = regularTimeOnCalendar()
    updateActiveTime(type, regular, false)
    schedulePendingEvent()
}

def updateActiveTime(type, regular, timeChanged = true) {
    int time = (regular.get(Calendar.HOUR_OF_DAY) * 100) + regular.get(Calendar.MINUTE)
    def regularTime = regular.getTimeInMillis()
    if (debugLogging)
        log.debug "Regular time is " + regular.getTime()
    
    // plag
    regular.add(Calendar.MINUTE, -plagoffset.intValue())
    def plagTime = regular.getTimeInMillis()
    if (debugLogging)
        log.debug "Plag time is " + regular.getTime()
    
    // early
    Calendar earlyTimeCal = Calendar.getInstance()
    earlyTimeCal.setTime(toDateTime(earlyShabbatTime))
    regular.set(Calendar.HOUR_OF_DAY, earlyTimeCal.get(Calendar.HOUR_OF_DAY))
    regular.set(Calendar.MINUTE, earlyTimeCal.get(Calendar.MINUTE))
    regular.set(Calendar.SECOND, 0)
    def earlyTime = regular.getTimeInMillis()
    if (debugLogging)
        log.debug "Early time is " + regular.getTime()
    
    int earlyTimeCode = (regular.get(Calendar.HOUR_OF_DAY) * 100) + regular.get(Calendar.MINUTE)
    
    def activeTime = null
    def prevEarlyOption = state.hasEarlyOption
    
    if (!timeChanged && type != "Regular") {
        saveType(MANUAL_EARLY, type)
    }
    
    int codeDiff = earlyTimeCode - time
    if (debugLogging) {
        log.debug "codeDiff=${codeDiff}"
    }
    
    boolean earlyOption = codeDiff <= 10
    if (earlyOption) {
        if (timeChanged && prevEarlyOption != null && prevEarlyOption.booleanValue() != earlyOption) {
            type = getPreviousType(SEASONAL)
            saveType(SEASONAL, null)
        }
        
        switch (type) {
            case "Regular":
                activeTime = regularTime
                break
        
            case "Plag":
                activeTime = plagTime
                break
        
            case "Early":
                activeTime = earlyTime
                break
        }
    }
    else {
        if (timeChanged && prevEarlyOption != null && prevEarlyOption.booleanValue() != earlyOption) {
            saveCurrentType(SEASONAL)
        }
        
        activeTime = regularTime
        type = "Regular"
    }
    
    if (timeChanged)
        state.hasEarlyOption = earlyOption
    
    device.updateDataValue("activeType", type)
    device.updateDataValue("activeTime", activeTime.toString())
    device.updateDataValue("earlyTime", earlyTime.toString())
    device.updateDataValue("plagTime", plagTime.toString())
    sendEvent("name":"activeType", "value":type)
    sendEvent("name":"activeTime", "value":activeTime)
    sendEvent("name":"earlyTime", "value":earlyTime)
    sendEvent("name":"plagTime", "value":plagTime)
    updateTimes(earlyOption, earlyTime, plagTime, regularTime, type)
}

String declareJavascriptFunction(deviceid, String command) {
    String url = makerUrl + deviceid + "/" + command + "?access_token=" + accessToken
    String s = "var xhttp = new XMLHttpRequest();"
    s += "xhttp.open(\"GET\", \"" + url + "\", true);"
    s += "xhttp.send();"
    return s
}

String clickableBegin(String command) {
    if (makerUrl != null && accessToken != null)
        return "<div style=\"padding-bottom:12px\" onclick='javascript:" + declareJavascriptFunction(device.id, command) + "'>"
    
    return "<div style=\"padding-bottom:12px\">"
}

def updateTimes(boolean earlyOption, long earlyTime, long plagTime, long regularTime, String activeType) {
    def times
    
    SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd @ h:mm a")
    final String clickableEnd = "</div>"
    
    final String headerBegin = "<span style=\"border: 2px outset\">"
    final String headerEnd = "</span>"
    
    final String dimBegin = "<i>"
    final String dimEnd = "</i>"
    
    if (earlyOption) {
        String text = clickableBegin("plag")
        if (activeType == "Plag")
            text += headerBegin
        else
            text += dimBegin
        text += "Plag: " + sdf.format(new Date(plagTime))
        if (activeType == "Plag")
            text += headerEnd
        else
            text += dimEnd
        
        text += clickableEnd
        
        text += clickableBegin("early")
        if (activeType == "Early")
            text += headerBegin
        else
            text += dimBegin
        text += "Early: " + sdf.format(new Date(earlyTime))
        if (activeType == "Early")
            text += headerEnd
        else
            text += dimEnd
        
        text += clickableEnd
        
        text += clickableBegin("regular")
        if (activeType == "Regular")
            text += headerBegin
        else
            text += dimBegin
        text += "Zman: " + sdf.format(new Date(regularTime))
        if (activeType == "Regular")
            text += headerEnd
        else
            text += dimEnd
        
        text += clickableEnd
        
        times = text
    }
    else {
        times = sdf.format(new Date(regularTime))
    }
    
    sendEvent("name":"times", "value": times)
}

Calendar regularTimeOnCalendar() {
    regTime = device.currentValue("regularTime")
    if (regTime == null)
        log.error "Regular time is null"
    
    return regularTimeOnCalendar(regTime)
}

Calendar regularTimeOnCalendar(currTime) {
    Calendar cal = Calendar.getInstance()
    cal.setTimeInMillis(currTime.longValue())
    return cal
}

def push(num) {
    if (debugLogging)
        log.debug "map: ${state.savedTypes}"
    
    switch (num.toInteger()) {
        case 0:
            plag()
            break
        
        case 1:
            early()
            break
        
        case 2:
            regular()
            break
    }
}
    
Date getActiveTime() {
    Object time
    
    activeType = getActiveType()
    switch (activeType) {
        case "Regular":
            time = device.getDataValue("regularTime")
            if (time == null)
                time = device.currentValue("regularTime")
        
            break
        
        case "Plag":
            time = device.getDataValue("plagTime")
            if (time == null)
                time = device.currentValue("plagTime")
        
            break
        
        case "Early":
            time = device.getDataValue("earlyTime")
            if (time == null)
                time = device.currentValue("earlyTime")
        
            break
    }
    
    if (debugLogging)
        log.debug "Active time for ${activeType} is ${time}"
    
    if (time != null) {
        if (time instanceof BigDecimal)
            time = new Date(time.longValue())
        else if (time instanceof Number)
            time = new Date(time.longValue())
        else if (time instanceof String)
            time = new Date(Long.parseLong(time))
    
        if (!(time instanceof Date))
            time = toDateTime(time)
    }
    
    return time
}
