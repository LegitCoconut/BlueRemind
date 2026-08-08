# AGP generates keep rules from the manifest and the layouts, so the activity, the
# application class, the receiver and every custom view survive without help here.
#
# The one thing R8 cannot see is the reflective lookup of BluetoothDevice.isConnected().
# That is a framework class, never part of this dex, so it is not renamed or stripped.
# Nothing to keep. This file exists so the release build has somewhere to put a rule
# the day one is actually needed.
