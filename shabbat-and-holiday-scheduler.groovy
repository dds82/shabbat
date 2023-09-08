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

@Field static final String NONE = "None"
@Field static final String PESACH = "Pesach"
@Field static final String SHAVUOT = "Shavuot"
@Field static final String SUKKOT = "Sukkot"
@Field static final String ROSH_HASHANA = "Rosh Hashana"
@Field static final String YOM_KIPPUR = "Yom Kippur"
@Field static final String SHMINI_ATZERET = "Shmini Atzeret"

@Field static final String SHABBAT_HAGADOL = "Shabbat HaGadol"
@Field static final String SHABBAT_SHUVA = "Shabbat Shuva"

@Field static final String HOLIDAY = "holiday"
@Field static final String MAJOR_HOLIDAY = "major"
@Field static final String SHABBAT = "shabbat"
@Field static final String CANDLES = "candles"
@Field static final String HAVDALAH = "havdalah"

@Field static final String REGULAR = "Regular"
@Field static final String EARLY = "Early"
@Field static final String PLAG = "Plag"

@Field static final String HAVDALAH_NONE = "None"
@Field static final String HAVDALAH_FIRE = "Fire"
@Field static final String HAVDALAH_NO_FIRE = "No Fire"

@Field static final Random random = new Random()

@Field static final Set fetchInProgress = new HashSet<>()

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
        attribute "activeType", "enum", [REGULAR, PLAG, EARLY]
        attribute "havdalah", "enum", [HAVDALAH_NONE, HAVDALAH_FIRE, HAVDALAH_NO_FIRE]
        attribute "specialHoliday", "enum", [NONE, PESACH, SHAVUOT, SUKKOT, ROSH_HASHANA, YOM_KIPPUR, SHMINI_ATZERET]
        attribute "holidayDay", "number" // Usually 1 or 2; may be 3 for Thur-Fri-Sat holidays. Or -1 if there is no holiday
        attribute "specialShabbat", "enum", [NONE, SHABBAT_HAGADOL, SHABBAT_SHUVA]
        command "regular"
        command "plag"
        command "early"
        command "havdalahMade"
        command "unscheduleAllEvents"
        command "testEvent", [[name:"Type*", type:"ENUM", constraints: [CANDLES,HAVDALAH,HOLIDAY]], [name:"Delay*", type:"NUMBER", description:"How long from, in seconds, from when the testEvent button is pushed to schedule this event"], [name:"Holiday", type:"ENUM", constraints:[PESACH, SHAVUOT, SUKKOT, YOM_KIPPUR, SHMINI_ATZERET]]]
        command "forceHoliday", [[name:"Holiday", type:"ENUM", constraints:[NONE, PESACH, SHAVUOT, SUKKOT, YOM_KIPPUR, SHMINI_ATZERET]]]
        command "forceDate", [[name: "Year*", type:"STRING"], [name: "Month*", type:"STRING"], [name: "Day*", type:"STRING"]]
        command "incrementForcedDate"
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
        input name: "israel", type: "bool", title: "Israeli Yom Tov Schedule", defaultValue: false
        input name: "ignoreHavdalahOnFireAfter", type: "number", title: "Assume Havdalah has already been made after this much time", required: false, defaultValue: 60, description: "Enter minutes, or 0 to disable this feature"
        input name: "preferredTime", type: "enum", title: "Preferred Shabbat Time", options: [PLAG, EARLY, REGULAR], description: "Preferred time to make Shabbat", defaultValue: REGULAR
        input name: "makerApiAppID", type: "string", title: "Maker API App ID", required: false, description: "The Maker API App's app ID.  For example: https://cloud.hubitat.com/api/[UUID]/apps/[AppID]..."
        input name: "hubUUID", type: "string", title: "Hub UUID", required: false, description: "The Hub's UUID for the maker API, for cloud access.  For example: https://cloud.hubitat.com/api/[UUID]/apps/[AppID]..."
        input name: "accessToken", type: "string", title: "Maker API access token", required: false, description: "Access token for the maker API"
        input name: "debugLogging", type: "bool", title: "Debug Logging", defaultValue: true
        input name: "refreshOnSave", type: "bool", title: "Refresh on Save", required: false, description: "If you don't know what this does, leave it turned ON", defaultValue: true
        input name: "shabbatstartoffset", type: "number", title: "Mode Switch Offset", required: false, defaultValue: 0, description: "Switch the Hub into Shabbat mode this many minutes before candle lighting"
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
}

def createStateMap() {
    if (state.savedTypes == null)
         state.savedTypes = new HashMap()
    
    if (state.eventList == null)
        state.eventList = new ArrayList()
}
 
 def initialize() {
     doUnschedule()
     createStateMap()
     
     boolean refetch = false
     if (state.nextEventTime == null) {
         if (debugLogging)
             log.debug "initialize(), state has no next event time, re-fetching"
         
         refetch = true
     }
     else if (state.eventList == null || state.eventList.isEmpty()) {
         Date pendingEvent = objectToDateTime(state.nextEventTime)
         if (pendingEvent != null && pendingEvent.getTime() > now()) {
             if (debugLogging)
                 log.debug "initialize(), state event list has no data but pending event is in the future, not re-fetching"
         }
         else {
             if (debugLogging)
                 log.debug "initialize(), state event list has no data, re-fetching"
         
             refetch = true
         }
     }
     
     if (refetch) {
         fetchSchedule()
     }
     else {
         if (debugLogging)
             log.debug "initialize(), state event list has data, not re-fetching"
         
         try {
             schedulePendingEvent()
         }
         finally {
             scheduleFetchTask()
         }
     }
 }

def scheduleFetchTask() {     
    String scheduleStr = String.format("%d %d %d 1 1 ?", random.nextInt(60), random.nextInt(59) + 1, random.nextInt(6))
     if (debugLogging)
         log.debug "schedule fetcher cron string is " + scheduleStr
     
     schedule(scheduleStr, fetchSchedule, [overwrite: true])
 }

def refresh() {
    fullReset()
}

def unscheduleAllEvents() {
    doUnschedule()
    List eventList = state.eventList
    if (eventList != null)
        eventList.clear()
    
    if (debugLogging) {
        log.debug "unscheduleAllEvents event list is now ${eventList}"
    }
}

def fullReset() {
    log.info "fullReset"
    resetData()
    state.timeOverride = null
    initialize()
}

void resetData() {
    sendEvent("name": "specialHoliday", "value": NONE)
    sendEvent("name": "specialShabbat", "value": NONE)
    sendEvent("name": "holidayDay", "value": -1)
    sendEvent("name": "havdalah", "value": HAVDALAH_NONE)
    state.initializing = true
    state.specialHoliday = null
    state.specialShabbat = null
    state.expectEmptyList = true
    state.eventList = null
    state.nextEventTime = null
}

def configure() {
    initialize()
}

def updated() {
    if (refreshOnSave)
        refresh()
    else
        initialize()
 }

def uninstalled() {
    doUnschedule()
}

def doUnschedule() {
    unschedule(fetchSchedule)
    unschedule(shabbatStart)
    unschedule(shabbatEnd)
    unschedule(havdalahMade)
}

def forceHoliday(String holiday) {
    state.holidayDay = 1
    sendEvent("name": "specialHoliday", "value": holiday)
    sendEvent("name": "holidayDay", "value": 1)
}

def testEvent(String eventType, BigDecimal delay, String holiday) {
    log.info "Received test request: eventType=${eventType}, delay=${delay}, holiday=${holiday}"
    List eventList = state.eventList
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
            if (debugLogging)
                log.debug "Fetch already in progress, ignoring fetch request"
            
            return
        }
    }
    
    state.expectEmptyList = false
    TimeZone tz = location.timeZone
    BigDecimal latitude = location.latitude
    BigDecimal longitude = location.longitude
    String zip = location.zipCode
    String geo = (tz != null && latitude != null && longitude != null ? "pos" : zip != null ? "zip" : "none")
    String locationParams = ""
    
    Calendar cal = Calendar.getInstance()
    if (state.timeOverride != null)
        cal.setTimeInMillis(state.timeOverride)
    
    int year = cal.get(Calendar.YEAR)
    
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
    
    String url = String.format("https://www.hebcal.com/hebcal/?v=1&cfg=json&i=%s&maj=on&min=off&mod=off&nx=off&year=%d&month=x&s=off&leyning=off&ss=on&mf=off&c=on&geo=%s&%s&M=on&s=off&b=%d", (israel ? "on" : "off"), year, geo, locationParams, candlelightingoffset)
    
    if (debugLogging)
        log.debug "url is " + url
    
    Map data = [test: testEventType, delay: testEventDelay, holiday: testHoliday]
    httpGet(url, {response -> scheduleUpdater(response, data)})
}

def scheduleUpdater(response, data) {
    try {
        state.fetching = true
        state.queuedEvents = [:]
        if (response.getStatus() != 200) {
            log.error "Error fetching schedule data: ${response.getStatus()}: ${response.getErrorData()}"
            state.expectEmptyList = true
            if (debugLogging)
                log.debug "Scheduling refetch in 30 minutes"
            
            runIn(30 * 60, fetchSchedule)            
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
    
        List eventList = state.eventList
        if (eventList == null) {
            eventList = new ArrayList()
            state.eventList = eventList
        }
        else {
            eventList.clear()
        }
    
        for (item in result.items) {
            if (item.category == CANDLES || item.category == HAVDALAH)
                eventList.add([type: item.category, when: toDateTime(item.date)])
            else if (item.category == HOLIDAY) {
                if (item.subcat == MAJOR_HOLIDAY) {
                    if (item.title.contains("Erev")) {
                        eventList.add([type: item.category, name: item.title, when: toDateTime(item.date)])
                    }
                    else if (item.title == SHMINI_ATZERET) {
                        // There is no "Erev" shmini atzeret, so offset it manually
                        Date eventDate = toDateTime(item.date)
                        Calendar offsetCal = Calendar.getInstance()
                        offsetCal.setTime(eventDate)
                        offsetCal.add(Calendar.DAY_OF_MONTH, -1)
                    
                        // Add it as one before the end because it needs to be before the candles event from the previous day
                        eventList.add(eventList.size() - 1, [type: item.category, name: "Erev " + item.title, when: offsetCal.getTime()])
                    }
                }
                else if (item.subcat == SHABBAT) {
                    if (item.title.equalsIgnoreCase(SHABBAT_HAGADOL) || item.title.equalsIgnoreCase(SHABBAT_SHUVA)) {
                        // Add it as one before the end because it needs to be before the candles event from the previous day
                        Date eventDate = toDateTime(item.date)
                        Calendar offsetCal = Calendar.getInstance()
                        offsetCal.setTime(eventDate)
                        offsetCal.add(Calendar.DAY_OF_MONTH, -1)
                        eventList.add(eventList.size() - 1, [type: SHABBAT, name: item.title, when: offsetCal.getTime()])
                    }
                }
            }
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
        synchronized (fetchInProgress) {
            fetchInProgress.remove(device.id)
        }
        
        state.fetching = false
        if (state.modeAfterFetch != null && (notWhen == null || location.getMode() != notWhen)) {
            if (location.getMode() != state.modeAfterFetch) {
                log.info "Setting mode after fetch to ${state.modeAfterFetch}"
                doSetMode(state.modeAfterFetch)
            }
            else log.info "Not seting mode after fetch because it already was ${state.modeAfterFetch}"
        }
        
        log.debug "Queued events: ${state.queuedEvents}"
        state.queuedEvents.each {key, val ->
            sendEvent("name": key, "value": val)
        }
        
        state.modeAfterFetch = null
        state.queuedEvents = null
        
        scheduleFetchTask()
    }
}

void sendEventIfNotFetching(Map event) {
    if (state.fetching) {
        log.debug "Queueing event ${event}"
        state.queuedEvents[event.name] = event.value
    }
    else {
        sendEvent("name": event.name, "value": event.value)
    }
}

void doSetMode(mode) {
    if (state.timeOverride == null) {
        location.setMode(mode)
    }
    else {
        log.info "Not setting mode to ${mode} because time override is set"
    }
}

int dayCompare(long time1, long time2) {
    Calendar cal = Calendar.getInstance()
    cal.setTimeInMillis(time1)
    
    Calendar cal2 = Calendar.getInstance()
    cal2.setTimeInMillis(time2)
    
    int yearComp = (cal.get(Calendar.YEAR) - cal2.get(Calendar.YEAR)) * 10000
    int monthComp = (cal.get(Calendar.MONTH) - cal2.get(Calendar.MONTH)) * 100
    int dayComp = (cal.get(Calendar.DAY_OF_MONTH) - cal2.get(Calendar.DAY_OF_MONTH))
    
    int result =  yearComp + monthComp + dayComp
    if (debugLogging) log.debug "cal=${cal.getTime()}, cal2=${cal2.getTime()}, result=${result}"
    return result
}

def forceDate(String year, String month, String day) {
    resetData()
    SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd")
    Date date = fmt.parse(year+month+day)
    state.timeOverride = date.getTime()
    initialize()
}

def incrementForcedDate() {
    if (state.timeOverride == null) log.error "Time override is not set"
    else {
        Calendar cal = Calendar.getInstance()
        cal.setTimeInMillis(state.timeOverride)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        Date newTime = cal.getTime()
        state.timeOverride = newTime.getTime()
        
         Object nextEventTime = state.nextEventTime
        if (!(nextEventTime instanceof Date))
            nextEventTime = toDateTime(nextEventTime.toString())
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd")
        log.info "Time override is now ${sdf.format(newTime)}"
        
        state.expectEmptyList = false
        if (newTime.after(nextEventTime)) {        
            if (state.nextEventType == CANDLES)
                shabbatStart()
            else if (state.nextEventType == HAVDALAH)
                shabbatEnd()
            else
                scheduleNextShabbatEvent()
        }
        else log.info "Time override is before next event time (${sdf.format(nextEventTime)})"
    }
}

def scheduleNextShabbatEvent() {
    state.nextEventTest=false
    long currentTime = state.timeOverride == null ? now() : state.timeOverride
    
    List eventList = state.eventList
    
    if ((eventList == null || eventList.isEmpty())) {
        if (!state.expectEmptyList) {
            // Re-fetch
            fetchSchedule()
        }
        else {
            state.specialHoliday = null
            state.nextEventType = null
            state.nextEventTime = null
            state.specialShabbat = null
        }
        
        return
    }
    
    boolean hadOldEvents = false
    int mostRecentHolidayComp = 1
    while (!eventList.isEmpty()) {
        Map data = eventList.remove(0)
        if (debugLogging)
            log.debug "Current event data is " + data
        
        String currentEventType = data.type
        if (debugLogging)
            log.debug "Current event type is " + currentEventType
        
        boolean old = false
        Date eventTime = objectToDateTime(data.when)
        if (eventTime.getTime() < currentTime && !data["isTest"]) {            
            old = true
            hadOldEvents = true
            if (debugLogging) log.debug "Old event found at ${eventTime}, current time is ${currentTime}"
        }
        
        if (currentEventType == HOLIDAY) {
            saveCurrentType(HOLIDAY, true)
            regular(false)
            
            if (data.name.contains(SHAVUOT)) {
                state.specialHoliday = SHAVUOT
            }
            else if (data.name.contains(PESACH)) {
                state.specialHoliday = PESACH
            }
            else if (data.name.contains(SUKKOT)) {
                state.specialHoliday = SUKKOT
            }
            else if (data.name.contains(ROSH_HASHANA)) {
                state.specialHoliday = ROSH_HASHANA
            }
            else if (data.name.contains(YOM_KIPPUR)) {
                state.specialHoliday = YOM_KIPPUR
            }
            else if (data.name.contains(SHMINI_ATZERET)) {
                state.specialHoliday = SHMINI_ATZERET
            }
            
            mostRecentHolidayComp = dayCompare(eventTime.getTime(), currentTime)
        }
        else if (currentEventType == SHABBAT) {
            state.specialShabbat = data.name
        }
        else {
            String type = data.type
            
            if (old) {
                if (debugLogging)
                    log.debug "Skipping current event data because it is in the past"
                
                if (type == HAVDALAH) {
                    if (debugLogging) log.debug "calling specialHolidayEnd(false) ${state.specialHoliday}"
                    specialHolidayEnd(false)
                }
            }
            
            if (hadOldEvents) {
                if (mostRecentHolidayComp < 0)
                    specialHolidayStart()
                else
                    updateSpecialShabbat()
            }
            
            state.nextEventType = type
            state.nextEventTime = objectToDateTime(data.when)
            if (data["isTest"])
                state.nextEventTest = true
            
            if (eventList.isEmpty())
                state.expectEmptyList = true
            
            schedulePendingEvent()
            break
        }
    }
    
    if (debugLogging) {
        log.debug "scheduleNextShabbatEvent Events remaining: ${eventList}"
    }
    
    if (eventList == null || eventList.isEmpty()) {
        state.expectEmptyList = true
        state.initializing = false
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
                switch (preferredTime) {
                    case PLAG:
                        plag()
                        break
                    
                    case EARLY:
                        early()
                        break
                    
                    case REGULAR:
                    default:
                        regular()
                        break
                }
                
                state.initializing = false
            }
            
            nextEventTime = getActiveTime()
        }
        
        if (debugLogging) {
            log.debug "schedulePendingEvent, nextEventTime is ${nextEventTime}, regular time is ${state.regularTime}"
        }
        
        long timeCompare = state.timeOverride == null ? now() : state.timeOverride
        if (nextEventTime.getTime() < timeCompare) {
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
        
        if (shabbatstartoffset != null && shabbatstartoffset > 0 && location.getMode() != startMode) {
            // The start offset shouldn't apply to the second day of a multi-day holiday
            cal.add(Calendar.MINUTE, -(shabbatstartoffset as int))
        }
        
        String scheduleStr = String.format("%d %d %d %d %d ? %d", cal.get(Calendar.SECOND), cal.get(Calendar.MINUTE), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
                
        if (debugLogging) {
            log.debug "schedule cron string is ${scheduleStr}"
            log.debug "next event is ${state.nextEventType} at ${nextEventTime}, holiday=${state.specialHoliday}"
        }
        
        try {
            if (state.nextEventType == CANDLES) {
                schedule(scheduleStr, shabbatStart)
            }
            else
                schedule(scheduleStr, shabbatEnd)
        }
        catch (Exception e) {
            if (state.timeOverride == null) throw e
        }
    }
    else if (debugLogging) {
        log.debug "No pending event found"
    }
    
    List eventList = state.eventList
    if (debugLogging) {
        log.debug "schedulePendingEvent Events remaining: ${eventList}"
    }
    
    if ((eventList == null || eventList.isEmpty()) && !state.expectEmptyList) {
        // Re-fetch
        fetchSchedule()
    }
}

def shabbatStart() {
    if (notWhen != null && location.getMode() != notWhen) {
        log.info "shabbatStart holiday=${state.specialHoliday}"
        
        if (location.getMode() != startMode) {
            havdalahMade()
            if (state.fetching) {
                log.info "Fetching, recording mode start ${startMode}"
                state.modeAfterFetch = startMode
            }
            else {
                log.info "shabbatStart setting mode to ${startMode}"
                doSetMode(startMode)
            }
        }
        
        specialHolidayStart()
    }
    else {
        if (debugLogging)
            log.debug "Not setting mode to " + startMode + " because mode is " + notWhen
    }
    
    shabbatEventTriggered()
}

void specialHolidayStart() {
    updateSpecialShabbat()
    fireSpecialHolidayEvent(state.specialHoliday)
}

void updateSpecialShabbat(boolean fireEvent = true) {
    if (state.specialShabbat != null) {
        if (fireEvent)
            sendEventIfNotFetching("name": "specialShabbat", "value": state.specialShabbat)
        
        state.specialShabbat = null
    }
    else {
        if (fireEvent)
            sendEventIfNotFetching("name": "specialShabbat", "value": NONE)
    }
}

def shabbatEnd() {
    String aish = HAVDALAH_NO_FIRE     
    boolean unset = location.getMode() != endMode && (notWhen == null || location.getMode() != notWhen)
    
    if (state.fetching) {
        log.info "Fetching, recording mode end ${endMode}"
        state.modeAfterFetch = endMode
    }
    else if (unset) {
        log.info "shabbatEnd setting mode to " + endMode
        doSetMode(endMode)
    
        Calendar cal = Calendar.getInstance()
        if (cal.get(Calendar.DAY_OF_WEEK) == 7 || state.specialHoliday == YOM_KIPPUR) {
            log.info "Need havdalah on fire..."
            aish = HAVDALAH_FIRE
            if (ignoreHavdalahOnFireAfter != null && ignoreHavdalahOnFireAfter > 0)
                runIn(ignoreHavdalahOnFireAfter * 60, havdalahMade)
        }

    
        sendEventIfNotFetching("name": "havdalah", "value": aish)
    }
    else sendEventIfNotFetching("name": "havdalah", "value": HAVDALAH_NONE)
    
    specialHolidayEnd()
    
    // Schedule the next event before restoring the previous type to avoid rescheduling the same pending event infinitely
    shabbatEventTriggered()
    
    restorePreviousType(HOLIDAY, true)
    if (preferredTime != REGULAR)
        restorePreviousManualType()
    
    // For Sukkot, reset the holidayDay
    state.holidayDay = -1
    sendEventIfNotFetching("name": "holidayDay", "value": state.holidayDay)
}

void specialHolidayEnd(boolean fireEvent = true) {
    updateSpecialShabbat(fireEvent)
    String nextSpecialHoliday = state.specialHoliday
    if (state.specialHoliday != SUKKOT) {
        nextSpecialHoliday = null
    }
    
    state.specialHoliday = nextSpecialHoliday
    if (fireEvent)
        fireSpecialHolidayEvent(nextSpecialHoliday)
}

void fireSpecialHolidayEvent(String nextSpecialHoliday) {
    if (debugLogging) log.debug "fireSpecialHolidayEvent ${nextSpecialHoliday}"
    if (nextSpecialHoliday == null || nextSpecialHoliday.isEmpty()) {
        state.holidayDay = -1
        sendEventIfNotFetching("name": "specialHoliday", "value": NONE)
    }
    else {
        if (state.holidayDay <= 0)
            state.holidayDay = 1
        else
            state.holidayDay++
            
        sendEventIfNotFetching("name": "specialHoliday", "value": nextSpecialHoliday)
    }
    
    sendEventIfNotFetching("name": "holidayDay", "value": state.holidayDay)
}

def havdalahMade() {
    if (debugLogging) {
        log.debug "havdalahMade()"
    }
    
    sendEventIfNotFetching("name": "havdalah", "value": HAVDALAH_NONE)
    unschedule(havdalahMade)
}

def shabbatEventTriggered() {
    scheduleNextShabbatEvent()
}

def setRegularTime(long date) {
    log.debug "setRegularTime ${new Date(date)}"
    state.regularTime = date
    sendEventIfNotFetching("name":"regularTime", "value":date)
    def activeType = getActiveType()

    updateActiveTime(activeType, regularTimeOnCalendar(date))
}

def getActiveType() {
    def activeType = state.activeType
    
    if (activeType == null)
        activeType = preferredTime
    
    return activeType
}

def plag(forceReschedule = true) {
    updateActiveTime("Plag", forceReschedule)
}

def regular(forceReschedule = true) {
    updateActiveTime("Regular", forceReschedule)
}

def early(forceReschedule = true) {
    updateActiveTime("Early", forceReschedule)
}

def saveCurrentType(key, onlyIfNotSet=false) {
    saveType(key, state.activeType, onlyIfNotSet)
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

def updateActiveTime(type, boolean forceReschedule = true) {
    if (debugLogging) log.debug "updateActiveTime, forceReschedule=${forceReschedule}"
    Calendar regular = regularTimeOnCalendar()
    if (regular != null) {
        updateActiveTime(type, regular, false)
        if (!state.initializing && forceReschedule) {
            if (debugLogging) log.debug "updateActiveTime scheduling pending event"
            schedulePendingEvent()
        }
        else {
            if (debugLogging) log.debug "updateActiveTime not scheduling pending event"
        }
    }
    else if (debugLogging) log.debug "updateActiveTime no regular time available yet, not scheduling event"
}

def updateActiveTime(type, regular, boolean timeChanged = true) {
    int regMin = regular.get(Calendar.MINUTE)
    double regFraction = (double)regMin / 60
    int regPct = Math.floor(regFraction * 100)
    int time = (regular.get(Calendar.HOUR_OF_DAY) * 100) + regPct
    
    // regular
    def regularTime = regular.getTimeInMillis()
    if (debugLogging) {
        log.debug "Regular time is " + regular.getTime()
        log.debug "Regular time code is " + time
    }
    
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
    
    int earlyMin = regular.get(Calendar.MINUTE)
    double earlyFraction = (double)earlyMin / 60
    int earlyPct = Math.floor(earlyFraction * 100)
    
    int earlyTimeCode = (regular.get(Calendar.HOUR_OF_DAY) * 100) + earlyPct
    
    def activeTime = null
    def prevEarlyOption = state.hasEarlyOption
    
    if (!timeChanged && type != "Regular") {
        saveType(MANUAL_EARLY, type)
    }
    
    int codeDiff = earlyTimeCode - time
    
    if (debugLogging) {
        log.debug "Early time code is " + earlyTimeCode
        log.debug "codeDiff=${codeDiff}"
    }
    
    if (codeDiff > 0) {
        // Static "early" is later than regular
        earlyTime = regularTime
    }
    
    def eventDay = regular.get(Calendar.DAY_OF_WEEK)
    
    // A code diff of 17 corresponds to about 10 minutes
    boolean earlyOption = codeDiff <= 17 && eventDay == 6 && state.specialHoliday == null
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
    
    state.activeType = type
    state.earlyTime = earlyTime
    state.plagTime = plagTime
    sendEventIfNotFetching("name":"activeType", "value":type)
    sendEventIfNotFetching("name":"activeTime", "value":activeTime)
    sendEventIfNotFetching("name":"earlyTime", "value":earlyTime)
    sendEventIfNotFetching("name":"plagTime", "value":plagTime)
    updateTimes(earlyOption, earlyTime, plagTime, regularTime, type)
}

String declareJavascriptFunction(deviceid, String command) {
    String urlBuilder = "a=\"" + makerApiAppID + "\";"
    urlBuilder += "l=window.location.origin;"
    urlBuilder += "if (l.includes(\"cloud.hubitat.com\")) {"
    urlBuilder += "l+=\"/api/" + hubUUID + "/apps/\"+a;"
    urlBuilder += "}"
    urlBuilder += "else {"
    urlBuilder += "l+=\"/apps/api/\"+a;"
    urlBuilder += "}"
    urlBuilder += "l+=\"/devices/" + deviceid + "/"+ command + "\";"
    
    if (debugLogging)
        log.debug "urlBuilder length = " + urlBuilder.length()
    
    String s = urlBuilder + "h=new XMLHttpRequest();"
    s += "h.open(\"GET\",l+\"?access_token=" + accessToken + "\", true);"
    s += "h.send();"
    
    if (debugLogging)
        log.debug "s length = " + s.length()
    
    return s
}

String clickableBegin(String command) {
    if (makerUrl != null && accessToken != null)
        return "<div onclick='javascript:" + declareJavascriptFunction(device.id, command) + "'>"
    
    return "<div>"
}

def updateTimes(boolean earlyOption, long earlyTime, long plagTime, long regularTime, String activeType) {
    def times
    
    final String clickableEnd = "</div>"
    
    final String headerBegin = "<span style=\"border:2px outset\">"
    final String headerEnd = "</span>"
    
    final String dimBegin = ""
    final String dimEnd = ""
        
    if (earlyOption) {
        SimpleDateFormat smf = new SimpleDateFormat("MMM dd")
        SimpleDateFormat sdf = new SimpleDateFormat("h:mm")
        
        // For the month and day, it doesn't matter which date is formatted
        String text = smf.format(new Date(plagTime)) + "<br/>"
        text += clickableBegin("plag")
        if (activeType == "Plag")
            text += headerBegin
        else
            text += dimBegin
        text += "P: " + sdf.format(new Date(plagTime))
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
        text += "E: " + sdf.format(new Date(earlyTime))
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
        text += "Z: " + sdf.format(new Date(regularTime))
        if (activeType == "Regular")
            text += headerEnd
        else
            text += dimEnd
        
        text += clickableEnd
        
        times = text
    }
    else {
        String text = state.specialHoliday
        if (text != null) {
            text += ": "
        }
        else text = ""
        
        SimpleDateFormat smf = new SimpleDateFormat("EEE MMM dd")
        SimpleDateFormat sdf = new SimpleDateFormat("h:mm a")
        text += smf.format(new Date(regularTime)) + "<br/><br/>"
        text += sdf.format(new Date(regularTime))
        times = text
    }
    
    if (debugLogging)
        log.debug "Times length = " + times.length()
    
    sendEventIfNotFetching("name":"times", "value": times)
}

Calendar regularTimeOnCalendar() {
    regTime = state.regularTime
    if (regTime == null)
        log.info "Regular time is null, possibly the first event encountered is a Holiday"
    
    return regularTimeOnCalendar(regTime)
}

Calendar regularTimeOnCalendar(currTime) {
    if (currTime == null) return null
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
            time = state.regularTime        
            break
        
        case "Plag":
            time = state.plagTime
            break
        
        case "Early":
            time = state.earlyTime
            break
    }
    
    if (debugLogging)
        log.debug "Active time for ${activeType} is ${time}"
    
    return objectToDateTime(time)
}

Date objectToDateTime(time) {
    if (time != null) {
        if (time instanceof BigDecimal)
            time = new Date(time.longValue())
        else if (time instanceof Number)
            time = new Date(time.longValue())
        else if (time instanceof String) {
            try {
                time = new Date(Long.parseLong(time))
            }
            catch (NumberFormatException e) {
                // Failed to convert a timestamp, we'll try ISO format
                time = toDateTime(time)
            }
        }
    
        if (!(time instanceof Date))
            time = toDateTime(time)
    }
    
    return time
}
