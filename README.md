# Shabbat and Holiday Scheduler
Virtual device driver to schedule shabbat/yom tov start and end events and change location mode automatically.  Provides options for Plag, constant time early shabbat (with configurable time), and Zman.  Also detects certain holidays which may need special treatment (e.g. lights staying on longer).  Minimum required platform version 2.2.8.141.

Required setup: 2 location modes configured, one for normal operation and one Shabbat mode.  By default, the device uses "Home" as the normal mode and "Shabbat" as the Shabbat mode.

You can display the "times" attribute on a dashboard. If you configure the Maker API to have access to this device and fill in the two configuration options for it, you can click on any displayed time to use it for the upcoming week. Otherwise, the device supports 3 virtual buttons which can be "push"ed:

0 = Plag
1 = Early
2 = Zman

Configuration options should be self-explanatory.

The device will automatically change the location mode to the Shabbat mode and back to the normal mode at the appropriate times. The reason this is a device instead of an app is so that any app can subscribe to its events or read its exposed states.  The device exposes the following states:

plagTime - the time for the current Plag shabbat
earlyTime - the time for the current Early shabbat
regularTime - the time for the current Zman shabbat
activeTime - which of the previous three times will trigger the change to Shabbat mode
activeType - which type of Shabbat is currently active. Values: Plag, Early, Regular
havdalah - what type of Havdalah is currently required. Values (if set): Fire, No Fire.  This state is updated when Shabbat/Yom Tov ends and is set to "None" after a configured time (default 60 minutes).  You may also set it to "None" manually by using the havdalahMade() command.
specialHoliday - which holiday it currently is that may require special treatment (if any). Values (if set): Pesach, Shavuot, Sukkot, Yom Kippur

All times are in milliseconds since the epoch. Times do not update for the following week until Shabbat ends.
