# Shabbat and Holiday Scheduler
Hubitat virtual device driver to schedule Shabbat/Yom Tov start and end events and change location mode automatically based on the Hub's configured location data.

Available in Hubitat Package Manager. If you are installing this for the first time, I strongly recommend you use that.

Why a device instead of an app?
--
So it can expose extended state information to other apps that want to use it.  Read on for more information about what's exposed.

How it works
--
On the first day of every secular year, at a random time between midnight and 6am, uses the HebCal API to get the candle lighting and havdalah times for the entire year.  If this fails, it  retries every 30 minutes until it's successful.

As of v1.2.0, the schedule data is saved to the database, so it persists across Hub reboots.  If you change the Hub's location settings, you must issue a Refresh command on the Shabbat device to re-fetch the data for the new location.

Required setup
--
* Two location modes configured, one for normal operation and one Shabbat mode.
* Zip code, or all of Latitude, Longitude, and Time Zone, configured correctly in the hub's "Location and Modes" settings page.

Recommended setup
--
Configure the two modes as follows:
* Name one "Shabbat".
* Name one "Home".

These are the default values for the configuration options in the device, so if you use these names, you will have less to configure.

So what time is Shabbat, exactly?
--
You can choose from one of three:

* Plag (configured by a constant offset from the regular time)
* Early (configured as a constant time; this doesn't change each week)
* Zman (regular time; pulled from HebCal)

You can display the "times" attribute on a dashboard. If you configure the Maker API to have access to this device and fill in the two configuration options for it, you can click on any displayed time to use it for the upcoming week. Otherwise, the device supports 3 virtual buttons which can be "push"ed:

* 0 = Plag
* 1 = Early
* 2 = Zman

The "Plag" and "Early" times are only offered as options if the Zman time is no more than 10 minutes before the Early time.  Otherwise, it's the wrong time of year for early shabbat, and only the Zman time will be displayed and used.

When installed, the selection defaults to "Plag".  If you change the selected time, the device may revert it back to your previous selection after the following Shabbat ends, based on the following rules:

* If you switch from one early time to another (e.g. Plag to Early), or from Zman to an early time, the new selection will remain.
* If you switch from an early time to Zman, it depends on whether or not the "Prefer Early Shabbat" option is set.  If it is, the selection will revert back to the previously selected early time; if it isn't, it will remain on Zman.
* If an early time was selected and the time of year no longer allows for early Shabbat, it will begin using the Zman time automatically.  When early Shabbat begins again the next year, it will go back to that early time.

Holidays
--
In addition to automatically scheduling candle lighting and Havdalah events, the device will detect some holidays and set the "specialHoliday" attribute when the holiday begins.  See "Exposed states" for more information.

Supported holidays:
* Pesach (will only be set for the first days)
* Shavuot
* Sukkot (remains set from the first day through the start of Shmini Atzeret)
* Rosh Hashana
* Yom Kippur
* Shmini Atzeret

Additionally, as of version 1.3.0:
* Erev Purim
* Purim
* Erev Chanukah
* Chanukah

Example use case: on Pesach, your dining room light might need to turn off later than usual at night.

Early Shabbat is not offered as an option for holidays.

Havdalah
--
When Shabbat or Yom Tov ends, the device will set the "havdalah" attribute as appropriate.  See "Exposed states" for more information.

Example use case: you might want to dim the lights in a certain room if you will be using a candle for Havdalah.

Exposed states
--
* plagTime - the time for the current Plag shabbat
* earlyTime - the time for the current Early shabbat
* regularTime - the time for the current Zman shabbat
* activeTime - which of the previous three times will trigger the change to Shabbat mode
* activeType - which type of Shabbat is currently active. Values: Plag, Early, Regular
* havdalah - what type of Havdalah is currently required. Values: None, Fire, No Fire.  This state is updated when Shabbat/Yom Tov ends and is set to "None" after a configured time (default 60 minutes).  You may also set it to "None" manually by using the havdalahMade() command.
* specialHoliday - which holiday it currently is that may require special treatment (if any). Values (if set): Pesach, Shavuot, Sukkot, Rosh Hashana, Yom Kippur, Shmini Atzeret, Chanukah, Purim
* specialShabbat - currently, this only detects Shabbat HaGadol and Shabbat Shuva
* holidayDay - for holidays, what day of yom tov it is.  This is -1 when there is no current holiday.  It will usually be 1 or 2, but may be 3 if the holiday is Thursday and Friday (in which case Shabbat will be day 3). For Chanukah, this counts all 8 days.
* times - this attribute is meant to be displayed in a dashboard tile. Fun fact: if you have installed the Maker API, and configured the app ID, hub UUID, and access token in the device, you can change the current week's time (Plag/Early/Zman) by clicking (or tapping) on it.

All times are in milliseconds since the epoch. Times do not update for the following week until Shabbat ends.

FAQ
--
Why is Pesach only set for the first days?
* Because I wrote this for myself, and for my use cases, there's no need to treat the last days of Pesach any differently then a regular Shabbat.

Why is there no Simchat Torah?
* Because it's continuous with and no different than Shmini Atzeret (in Israel, it's the same day).  You can detect Simchat Torah outside of Israel by looking for specialHoliday="Shmini Atzeret" and holidayDay=2.

Known issues
--
* If you were set up for Plag or Early shabbat for one week, and the device already triggered the mode change to Shabbat, and you change your mind, clicking "Refresh" on the device can have unpredictable results.  If you end up in this situation and really need to get your house out of Shabbat mode, the best way to do that is to click "Unschedule all events", then manually take your house out of Shabbat mode, and then choose the correct time you want for that week.
* Because the device doesn't detect the last days of Pesach, it can potentially offer an early candle lighting time for the 7th day of Pesach if it starts on a Friday night.  I don't feel like fixing this right now.
