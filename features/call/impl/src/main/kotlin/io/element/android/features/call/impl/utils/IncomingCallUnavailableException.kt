package io.element.android.features.call.impl.utils

class IncomingCallUnavailableException(val ended: Boolean) : IllegalStateException("Incoming invitation is unavailable")
