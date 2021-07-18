import groovy.transform.Field
import java.math.BigDecimal
import java.util.Date
import java.util.Calendar
import java.util.HashMap
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

@Field List eventList = new ArrayList()
@Field final Random random = new Random()

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
        attribute "havdalahOnFire", "enum", ["true", "false"]
        attribute "specialHoliday", "enum", ["", PESACH, SHAVUOT, SUKKOT, YOM_KIPPUR, SHMINI_ATZERET]
        command "regular"
        command "plag"
        command "early"
        command "havdalahMade"
     }
 }

preferences
{
    section
    {
        input name: "candlelightingoffset", type: "number", title: "Candle Lighting (minutes before sunset)", required: true, defaultValue: 18
        input name: "earlyShabbatTime", type: "time", title: "Time for \"Early\" Shabbat", required: true, defaultValue: "19:00"
        input name: "startMode", type: "enum", title: "Mode at Shabbat Start", required:true, options: getModeOptions(), defaultValue: "Shabbat"
        input name: "endMode", type: "enum", title: "Mode at Shabbat End", required: true, options: getModeOptions(), defaultValue: "Home"
        input name: "notWhen", type: "enum", title: "Don't go into Shabbat mode if mode is...", options: getModeOptions(), required: false, defaultValue: "Away"
        input name: "ignoreHavdalahOnFireAfter", type: "number", title: "Assume Havdalah has already been made after this many minutes", required: false, defaultValue: 60
        input name: "makerUrl", type: "string", title: "Maker API base URL", required: false, description: "The base URL for the maker API, up to and including 'devices/'"
        input name: "accessToken", type: "string", title: "Maker API access token", required: false, description: "Access token for the maker API"
        input name: "debugLogging", type: "bool", title: "Debug Logging", defaultValue: true
        input name: "logOnly", type: "bool", title: "Log Events Only (don't change modes)", defaultValue: false
    }
}

List<String> getModeOptions() {
    List<String> options = new ArrayList<>()
    for (Object mode : location.getModes())
        options.add(mode.toString())
    
    return options
}

def installed() {
    state.initializing = true
    initialize()
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
     unschedule(fetchSchedule)
     createStateMap()
     
     schedulePendingEvent()
    fetchSchedule()
     
    String scheduleStr = String.format("%d %d %d 1 * ?", random.nextInt(60), random.nextInt(59) + 1, random.nextInt(6))
     if (debugLogging)
         log.debug "schedule fetcher cron string is " + scheduleStr
     
     schedule(scheduleStr, fetchSchedule, [overwrite: true])
 }

def configure() {
    initialize()
}

def updated() {
     initialize()
 }

def uninstalled() {
    unschedule(fetchSchedule)
}

def fetchSchedule() {
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
    
    asynchttpGet(scheduleUpdater, [uri : url])
}

def scheduleUpdater(response, data) {
    if (response.getStatus() != 200) {
        log.error response.getErrorData()
        return
    }
    
    result = parseJson(response.getData())
    if (debugLogging)
        log.debug "Response: " + result.items
    
    if (result.items.length == 0) {
        log.error "Schedule query returned no data"
        return
    }
    
    eventList.clear()
    for (item in result.items) {
        if (item.category == CANDLES || item.category == HAVDALAH)
            eventList.add([type: item.category, when: toDateTime(item.date)])
        else if (item.category == HOLIDAY && (item.title.contains("Erev") || item.title == SHMINI_ATZERET))
            eventList.add([type: item.category, name: item.title, when: toDateTime(item.date)])
    }
    
    if (debugLogging)
        log.debug eventList
    
    scheduleNextShabbatEvent()
}

def scheduleNextShabbatEvent() {
    long currentTime = now()
    while (!eventList.isEmpty()) {
        Map data = eventList.remove(0)
        if (debugLogging)
            log.debug "Current event data is " + data
        
        if (data.when.getTime() < currentTime) {
            if (debugLogging)
                log.debug "Skipping current event data because it is in the past"
            
            continue
        }
        
        String currentEventType = data.type
        if (debugLogging)
            log.debug "Current event type is " + currentEventType
        
        if (currentEventType == "holiday") {
            if (data.title.contains(SHAVUOT)) {
                state.specialHoliday = SHAVUOT
                saveCurrentType(SHAVUOT)
                regular()
            }
            else if (data.title.contains(PESACH)) {
                state.specialHoliday = PESACH
            }
            else if (data.title.contains(SUKKOT)) {
                state.specialHoliday = SUKKOT
            }
            else if (data.title.contains(YOM_KIPPUR)) {
                state.specialHoliday = YOM_KIPPUR
            }
            else if (data.title == SHMINI_ATZERET) {
                state.specialHoliday = null
            }
        }
        else {
            Calendar cal = Calendar.getInstance()
            cal.setTime(data.when)
            String type = data.type
            
            int month = cal.get(Calendar.MONTH)
            final boolean detectChanuka = month == Calendar.NOVEMBER || month == Calendar.DECEMBER || month == Calendar.JANUARY
            
            // HebCal doesn't report Havdalah on Chanuka
            if (detectChanuka && cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY && type == CANDLES) {
                type = HAVDALAH
            }
            
            state.nextEventType = type
            state.nextEventTime = data.when
            schedulePendingEvent()
            break
        }
    }
}

def schedulePendingEvent() {
    if (debugLogging) {
        log.debug "schedulePendingEvent, ${state.nextEventType} at ${state.nextEventTime}, initializing=${state.initializing}"
    }
    
    if (state.nextEventType != null && state.nextEventTime != null) {
        Object nextEventTime = state.nextEventTime
        if (!(nextEventTime instanceof Date))
            nextEventTime = toDateTime(nextEventTime.toString())
        
        if (nextEventTime.getTime() < now()) {
            if (debugLogging)
                log.debug "Not scheduling pending event because it is in the past"
            
            return
        }
        
        if (state.nextEventType == CANDLES) {
            setRegularTime(nextEventTime.getTime())
            if (state.initializing) {
                state.initializing = false
                plag()
            }
            
            nextEventTime = getActiveTime()
        }
        
        if (debugLogging) {
            log.debug "schedulePendingEvent, nextEventTime is ${nextEventTime}"
        }
        
        Calendar cal = Calendar.getInstance()
        cal.setTime(nextEventTime)
        
        String scheduleStr = String.format("%d %d %d %d %d ? %d", cal.get(Calendar.SECOND), cal.get(Calendar.MINUTE), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
        if (debugLogging) {
            log.debug "schedule cron string is " + scheduleStr
            log.debug "next event is " + state.nextEventType + " at " + nextEventTime + ", holiday=" + state.specialHoliday
        }
        
        if (state.nextEventType == CANDLES)
            schedule(scheduleStr, shabbatStart)
        else
            schedule(scheduleStr, shabbatEnd)
    }
    else if (logDebug) {
        log.debug "No pending event found"
    }
}

def shabbatStart() {
    if (notWhen != null && location.getMode() != notWhen) {
        if (location.getMode() != startMode) {
            log.info "shabbatStart setting mode to " + startMode + ", holiday=" + state.specialHoliday
            if (!logOnly) {
                location.setMode(startMode)
                sendEvent("name": "specialHoliday", value: (state.specialHoliday == null ? "" : state.specialHoliday))
            }
            
            state.processedStartEvent = true
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
    String aish = "false"
    if (state.processedStartEvent || logOnly) {
        state.processedStartEvent = false
        
        log.info "shabbatEnd setting mode to " + endMode
        
        if (!logOnly) {
            location.setMode(endMode)
        
            if (state.specialHoliday == SHAVUOT) {
                restorePreviousType(SHAVUOT)
            }
        
            if (state.specialHoliday != SUKKOT) {
                nextSpecialHoliday = null
            }
        }
        
        Calendar cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) == 7 || state.specialHoliday == YOM_KIPPUR) {
            log.info "Need havdalah on fire..."
            if (!logOnly) {
                aish = "true"
                if (ignoreHavdalahOnFireAfter != null && ignoreHavdalahOnFireAfter > 0)
                    runIn(ignoreHavdalahOnFireAfter * 60, havdalahMade)
            }
        }
    }
    else if (notWhen != null && location.getMode() == notWhen) {
        nextSpecialHoliday = null
    }
    
    state.specialHoliday = nextSpecialHoliday
    sendEvent("name": "specialHoliday", "value": (nextSpecialHoliday == null ? "" : nextSpecialHoliday))
    sendEvent("name": "havdalahOnFire", "value": aish)
    shabbatEventTriggered()
    restorePreviousManualType()
}

def havdalahMade() {
    sendEvent("name": "havdalahOnFire", "value": false)
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

def saveCurrentType(key) {
    saveType(key, device.currentValue("activeType"))
}

def saveType(key, type) {
    createStateMap()
    state.savedTypes[key] = type
}

def restorePreviousType(key) {
    def type = getPreviousType(key)
    if (type)
        updateActiveTime(type)
}

def restorePreviousManualType() {
    restorePreviousType(MANUAL_EARLY)
}

def getPreviousType(key) {
    createStateMap()
    return state.savedTypes[key]
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
    regular.add(Calendar.HOUR_OF_DAY, -1)
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
    
    def activeTime = null
    def prevEarlyOption = state.hasEarlyOption
    
    if (!timeChanged && type != "Regular") {
        saveType(MANUAL_EARLY, type)
    }
    
    boolean earlyOption = time >= 1850
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
            time = device.currentValue("regularTime")
            break
        
        case "Plag":
            time = device.currentValue("plagTime")
            break
        
        case "Early":
            time = device.currentValue("earlyTime")
            break
    }
    
    if (debugLogging)
        log.debug "Active time for ${activeType} is ${time}"
    
    if (time != null) {
        if (time instanceof BigDecimal)
            time = new Date(time.longValue())
    
        if (!(time instanceof Date))
            time = toDateTime(time)
    }
    
    return time
}
