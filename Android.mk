LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_STATIC_JAVA_LIBRARIES := ion AndroidAsync
LOCAL_JAVA_LIBRARIES := telephony-common

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := Babel
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
