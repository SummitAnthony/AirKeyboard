package com.airkeyboard.network

// Typed connect() failures for password-protected servers. Messages never carry secrets.

/** The PC wants a password and none was given (or the saved one is no longer right: [wrong]) */
class PasswordRequiredException(val serverName: String, val wrong: Boolean = false) :
    Exception(if (wrong) "Wrong password" else "Password required")

/** The PC ([serverName]) rejected the password the user typed */
class WrongPasswordException(val serverName: String) : Exception("Wrong password")

/** Too many wrong passwords from this tablet; the PC refuses logins for [seconds] */
class LockedOutException(val seconds: Int) :
    Exception("Too many wrong passwords. Try again in $seconds s.")

/** The PC accepted the login but could not prove it knows the password: not the real PC */
class ServerNotAuthenticException : Exception("This PC could not prove it knows the password")

/** The PC isn't running AirKeyboard.exe, or runs one with a login this app doesn't know */
class UnsupportedServerException :
    Exception("This PC isn't running AirKeyboard, or runs a version this app doesn't support.")
