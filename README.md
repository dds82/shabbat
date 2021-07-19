# shabbat
Virtual device driver to schedule shabbat/yom tov start and end events and change location mode automatically.  Provides options for Plag, constant time early shabbat (with configurable time), and Zman.  Also detects certain holidays which may need special treatment (e.g. lights staying on longer).

Required setup: 2 location modes configured, one for normal operation and one "Shabbat" mode.

You can display the "times" attribute on a dashboard. If you configure the Maker API to have access to this device and fill in the two configuration options for it, you can click on any displayed time to use it for the upcoming week.

Configuration options should be self-explanatory.
