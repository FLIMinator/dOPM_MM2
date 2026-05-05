/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package dopm_mm2.Devices;

import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import mmcorej.CMMCore;
import dopm_mm2.util.MMStudioInstance;

/**
 * Static helper class for controlling the PI mirror stage/controller.
 *
 * <p>This class wraps the PI ASCII commands used by the dOPM acquisition
 * runnables, especially the C-413 trigger output configuration.
 *
 * <p>Important conventions:
 * <ul>
 *   <li>Micro-Manager stage positions are normally in um.</li>
 *   <li>PI serial trigger distances/ranges in this class are sent in mm.</li>
 *   <li>Trigger output ID / device ID is usually 1 for this single-axis setup.</li>
 * </ul>
 *
 * <p>Revision notes:
 * <ul>
 *   <li>Fixed {@code setAndCheckSerial()} so verification cannot silently fail.</li>
 *   <li>Relaxed PI numeric comparison tolerance to a sensible mm-scale value.</li>
 *   <li>Added unambiguous {@code isPIStageReady()} and {@code isPIStageMoving()} helpers.</li>
 *   <li>Kept {@code checkPIMotion()} as a compatibility alias, but it now clearly means "ready".</li>
 *   <li>Fixed retry-loop conditions in serial retry helpers.</li>
 *   <li>Added logging to serial buffer clearing.</li>
 * </ul>
 *
 * @author lnr19
 */
public class PIStage {

    private static final Logger PIStageLogger =
            Logger.getLogger(PIStage.class.getName());

    public PIStage() {
    }

    // -------------------------------------------------------------------------
    // Basic movement helpers
    // -------------------------------------------------------------------------

    /**
     * Move the PI mirror stage using an input position in mm.
     *
     * <p>The Micro-Manager stage API expects um, so the input is converted
     * from mm to um before calling {@code setPosition()}.
     *
     * @param device Micro-Manager PI stage device name
     * @param position position in mm
     */
    public static void setPositionMillim(String device, double position) {
        try {
            MMStudioInstance.getCore().setPosition(device, position * 1e3);
            PIStageLogger.info(String.format(
                    "Set %s position to %.5f mm", device, position));
        } catch (Exception e) {
            PIStageLogger.severe(String.format(
                    "Failed to set %s to position %.5f mm with: %s",
                    device, position, e.getMessage()));
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // PI motion/readiness helpers
    // -------------------------------------------------------------------------

    /**
     * Compatibility method retained for older code.
     *
     * <p>Important: despite the historical name, this returns whether the stage
     * is READY, not whether it is moving.
     *
     * @param port PI controller COM port
     * @return true if the stage is on target and not moving
     * @throws Exception if the PI status query fails
     */
    public static boolean checkPIMotion(String port) throws Exception {
        return isPIStageReady(port, 1, 1);
    }

    /**
     * Compatibility method retained for older code.
     *
     * <p>Important: despite the historical name, this returns whether the stage
     * is READY, not whether it is moving.
     *
     * @param port PI controller COM port
     * @param device PI trigger/device ID
     * @param axis PI axis ID
     * @return true if the stage is on target and not moving
     * @throws Exception if the PI status query fails
     */
    public static boolean checkPIMotion(String port, int device, int axis)
            throws Exception {
        return isPIStageReady(port, device, axis);
    }

    /**
     * Return true if the PI stage is on target and not moving.
     *
     * <p>This uses the PI {@code SRG?} command. According to the original code
     * comments, the relevant status bits are:
     * <ul>
     *   <li>bit 15: on target</li>
     *   <li>bit 13: in motion</li>
     * </ul>
     *
     * @param port PI controller COM port
     * @return true if ready
     * @throws Exception if the query or PI error check fails
     */
    public static boolean isPIStageReady(String port) throws Exception {
        return isPIStageReady(port, 1, 1);
    }

    /**
     * Return true if the PI stage is on target and not moving.
     *
     * @param port PI controller COM port
     * @param device PI device ID
     * @param axis PI axis ID
     * @return true if ready
     * @throws Exception if the query or PI error check fails
     */
    public static boolean isPIStageReady(String port, int device, int axis)
            throws Exception {

        String msg = String.format("SRG? %d %d", device, axis);
        String ansHex;
        String err;

        try {
            CMMCore core = MMStudioInstance.getCore();

            core.setSerialPortCommand(port, msg, "\n");
            ansHex = core.getSerialPortAnswer(port, "\n");

            core.setSerialPortCommand(port, "ERR?", "\n");
            err = core.getSerialPortAnswer(port, "\n");

            if (!err.equals("0")) {
                String errMsg = String.format(
                        "Error %s when checking PI move status with SRG",
                        err);
                PIStageLogger.severe(errMsg);
                throw new Exception("SRG command: ERR? returned non-zero error " + err);
            }

            String hexStr = ansHex.split("=")[1].trim();
            hexStr = hexStr.replaceFirst("0x", "");

            int status = Integer.parseInt(hexStr, 16);

            boolean onTarget = ((status >> 15) & 1) == 1;
            boolean moving = ((status >> 13) & 1) == 1;

            PIStageLogger.info(String.format(
                    "PI SRG status | raw=%s | status=0x%s | onTarget=%s | moving=%s",
                    ansHex, hexStr, onTarget, moving));

            return onTarget && !moving;

        } catch (Exception e) {
            throw new Exception(
                    "Error checking PI stage readiness with SRG: "
                    + e.getMessage());
        }
    }

    /**
     * Return true if the PI stage is moving or not yet on target.
     *
     * @param port PI controller COM port
     * @return true if not ready
     * @throws Exception if readiness check fails
     */
    public static boolean isPIStageMoving(String port) throws Exception {
        return !isPIStageReady(port);
    }

    /**
     * Wait for the PI stage to become ready.
     *
     * @param port PI controller COM port
     * @param timeoutMs timeout in ms
     * @throws Exception if timeout occurs or PI status query fails
     */
    public static void waitForPIStageReady(String port, long timeoutMs)
            throws Exception {

        long start = System.currentTimeMillis();

        while (!isPIStageReady(port)) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw new TimeoutException(
                        "Timed out waiting for PI stage to become ready");
            }
            Thread.sleep(10);
        }
    }

    // -------------------------------------------------------------------------
    // Trigger output setup
    // -------------------------------------------------------------------------

    /**
     * Force the PI trigger output low.
     *
     * <p>This is useful before starting an acquisition because the PI trigger
     * may be OR-gated with other trigger sources.
     *
     * @param port PI controller COM port
     * @throws Exception if disabling trigger or setting DIO fails
     */
    public static void setPITriggerLow(String port) throws Exception {
        setPITriggerEnable(port, 1, 0);
        setPIDigitalOut(port, 1, 0);
    }

    /**
     * Configure basic PI trigger settings for position/distance triggering.
     *
     * <p>This disables triggering, sets the digital output low, selects axis 1,
     * and sets trigger mode 0, which is position/distance triggering.
     *
     * @param port PI controller COM port
     * @param device PI trigger/device ID
     * @throws Exception if any serial command fails
     */
    public static void setupPITriggering(String port, int device)
            throws Exception {

        clearPISerialOutBuffer(port);

        setPITriggerEnable(port, device, 0);
        setPIDigitalOut(port, device, 0);
        setPITriggerAxis(port, device, 1);
        setPITriggerMode(port, device, 0);
    }

    /**
     * Set the PI digital output level.
     *
     * @param port PI controller COM port
     * @param level output level, usually 0 or 1
     * @throws Exception if serial command fails
     */
    public static void setPIDigitalOut(String port, int level)
            throws Exception {
        setPIDigitalOut(port, 1, level);
    }

    /**
     * Set the PI digital output level.
     *
     * <p>The PI documentation recommends not using DIO while trigger output is
     * enabled, so this method first disables trigger output.
     *
     * @param port PI controller COM port
     * @param device PI trigger/device ID
     * @param level output level, usually 0 or 1
     * @throws Exception if serial command fails
     */
    public static void setPIDigitalOut(String port, int device, int level)
            throws Exception {

        setPITriggerEnable(port, device, 0);

        String msg = String.format("DIO %1$d %2$d", device, level);
        String queryMsg = String.format("DIO? %1$d", device);
        double expectedValue = level;

        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            PIStageLogger.severe(
                    "Failed to set PI digital output with " + e.getMessage());
            throw e;
        }
    }

    /**
     * Enable or disable PI trigger output.
     *
     * @param port PI controller COM port
     * @param trigOn 1 to enable, 0 to disable
     * @throws Exception if serial command fails
     */
    public static void setPITriggerEnable(String port, int trigOn)
            throws Exception {
        setPITriggerEnable(port, 1, trigOn);
    }

    /**
     * Enable or disable PI trigger output.
     *
     * @param port PI controller COM port
     * @param device PI trigger/device ID
     * @param trigOn 1 to enable, 0 to disable
     * @throws Exception if serial command fails
     */
    public static void setPITriggerEnable(String port, int device, int trigOn)
            throws Exception {

        String msg = String.format("TRO %1$d %2$d", device, trigOn);
        String queryMsg = String.format("TRO? %1$d", device);
        double expectedValue = trigOn;

        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            PIStageLogger.severe(
                    "Failed to set PI trigger enable with " + e.getMessage());
            throw e;
        }
    }

    /**
     * Set the PI trigger axis.
     *
     * @param port PI controller COM port
     * @param axis PI axis ID
     * @throws Exception if serial command fails
     */
    public static void setPITriggerAxis(String port, int axis)
            throws Exception {
        setPITriggerAxis(port, 1, axis);
    }

    /**
     * Set the PI trigger axis.
     *
     * <p>Uses CTO parameter 2.
     *
     * @param port PI controller COM port
     * @param device PI trigger/device ID
     * @param axis PI axis ID
     * @throws Exception if serial command fails
     */
    public static void setPITriggerAxis(String port, int device, int axis)
            throws Exception {

        String msg = String.format("CTO %1$d 2 %2$d", device, axis);
        String queryMsg = String.format("CTO? %1$d 2", device);
        double expectedValue = axis;

        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            PIStageLogger.severe(
                    "Failed to set PI trigger axis with " + e.getMessage());
            throw e;
        }
    }

    /**
     * Set the PI trigger mode.
     *
     * @param port PI controller COM port
     * @param triggerMode PI trigger mode
     * @throws Exception if serial command fails
     */
    public static void setPITriggerMode(String port, int triggerMode)
            throws Exception {
        setPITriggerMode(port, 1, triggerMode);
    }

    /**
     * Set the PI trigger mode.
     *
     * <p>For this acquisition path we normally use:
     * <ul>
     *   <li>0 = position/distance trigger</li>
     * </ul>
     *
     * @param port PI controller COM port
     * @param device PI trigger/device ID
     * @param triggerMode PI trigger mode
     * @throws Exception if serial command fails
     */
    public static void setPITriggerMode(String port, int device, int triggerMode)
            throws Exception {

        String msg = String.format("CTO %1$d 3 %2$d", device, triggerMode);
        String queryMsg = String.format("CTO? %1$d 3", device);
        double expectedValue = triggerMode;

        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            PIStageLogger.severe(
                    "Failed to set PI trigger mode with " + e.getMessage());
            throw e;
        }
    }

    /**
     * Set PI trigger distance in mm.
     *
     * <p>The runnable passes mirror trigger distance in mm to this method.
     *
     * @param port PI controller COM port
     * @param device PI trigger/device ID
     * @param triggerDistance trigger distance in mm
     * @throws Exception if serial command fails
     */
    public static void setPITriggerDistance(
            String port, int device, double triggerDistance)
            throws Exception {

        if (triggerDistance <= 0) {
            throw new IllegalArgumentException("PI trigger distance must be > 0");
        }

        String triggerDistanceStr = String.format("%.5f", triggerDistance);
        String msg = String.format("CTO %1$d 1 %2$s", device, triggerDistanceStr);
        String queryMsg = String.format("CTO? %1$d 1", device);
        double expectedValue = triggerDistance;

        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            PIStageLogger.severe(
                    "Failed to set PI trigger distance with " + e.getMessage());
            throw e;
        }
    }

    /**
     * Set PI trigger range in mm.
     *
     * <p>Uses CTO parameter 8 for lower range and CTO parameter 9 for upper
     * range.
     *
     * @param port PI controller COM port
     * @param device PI trigger/device ID
     * @param triggerRange two-element array [lower, upper] in mm
     * @throws Exception if serial command fails
     */
    public static void setPITriggerRange(
            String port, int device, double[] triggerRange)
            throws Exception {

        if (triggerRange == null || triggerRange.length != 2) {
            throw new IllegalArgumentException(
                    "triggerRange must be a two-element array [lower, upper]");
        }

        if (triggerRange[1] <= triggerRange[0]) {
            throw new IllegalArgumentException(String.format(
                    "PI trigger range upper must be > lower. Got [%.5f, %.5f]",
                    triggerRange[0], triggerRange[1]));
        }

        String lowerRangeStr = String.format("%.5f", triggerRange[0]);
        String upperRangeStr = String.format("%.5f", triggerRange[1]);

        String msgLower = String.format("CTO %1$d 8 %2$s", device, lowerRangeStr);
        String msgUpper = String.format("CTO %1$d 9 %2$s", device, upperRangeStr);

        String queryMsgLower = String.format("CTO? %1$d 8", device);
        String queryMsgUpper = String.format("CTO? %1$d 9", device);

        double expectedValueLower = triggerRange[0];
        double expectedValueUpper = triggerRange[1];

        PIStageLogger.info(String.format(
                "Setting PI trigger range to [%.5f, %.5f] mm",
                triggerRange[0], triggerRange[1]));

        try {
            setAndCheckSerial(port, msgLower, queryMsgLower, expectedValueLower);
            setAndCheckSerial(port, msgUpper, queryMsgUpper, expectedValueUpper);
        } catch (Exception e) {
            String errMsg = String.format(
                    "Failed to set PI trigger range with %s",
                    e.getMessage());
            PIStageLogger.severe(errMsg);
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Serial buffer / stop helpers
    // -------------------------------------------------------------------------

    /**
     * Drain stale replies from the PI serial output buffer.
     *
     * <p>This is a pragmatic guard against previous commands leaving unread
     * replies in Micro-Manager's serial buffer.
     *
     * @param port PI controller COM port
     */
    public static void clearPISerialOutBuffer(String port) {
        int maxClears = 100;
        int i = 0;

        while (i < maxClears) {
            try {
                MMStudioInstance.getCore().getSerialPortAnswer(port, "\n");
                i++;
            } catch (Exception e) {
                break;
            }
        }

        PIStageLogger.info(String.format(
                "Cleared %d stale PI serial replies from %s", i, port));
    }

    /**
     * Stop PI stage motion using STP.
     *
     * @param port PI controller COM port
     * @throws Exception if controller returns unexpected error
     */
    public static void stopPIStage(String port) throws Exception {
        stopOrHaltPIStage(port, "STP");
    }

    /**
     * Halt PI stage motion using HLT.
     *
     * @param port PI controller COM port
     * @throws Exception if controller returns unexpected error
     */
    public static void haltPIStage(String port) throws Exception {
        stopOrHaltPIStage(port, "HLT");
    }

    /**
     * Send STP or HLT and check PI error response.
     *
     * <p>The original code expected error code 10 after STP/HLT. That behaviour
     * is preserved here.
     *
     * @param port PI controller COM port
     * @param msg STP or HLT
     * @throws Exception if controller returns unexpected error
     */
    private static void stopOrHaltPIStage(String port, String msg)
            throws Exception {

        CMMCore core = MMStudioInstance.getCore();

        core.setSerialPortCommand(port, msg, "\n");
        core.setSerialPortCommand(port, "ERR?", "\n");

        String answerErr = core.getSerialPortAnswer(port, "\n");

        if (!answerErr.equals("10")) {
            throw new PIControllerErrorException(String.format(
                    "Error code %s received from PI controller after %s",
                    answerErr, msg));
        }
    }

    // -------------------------------------------------------------------------
    // Serial set/query/verify
    // -------------------------------------------------------------------------

    /**
     * Send a PI serial set command, query it back, and verify the value.
     *
     * <p>This method is intentionally strict about throwing if verification
     * fails. The previous version could exit the retry loop without throwing
     * when {@code isSet == false}.
     *
     * @param port PI controller COM port
     * @param msg command that sets the value
     * @param queryMsg command that queries the value
     * @param expectedValue expected numeric value
     * @throws TimeoutException if verification fails after retries
     * @throws IllegalStateException retained for compatibility
     */
    public static void setAndCheckSerial(
            String port, String msg, String queryMsg, Double expectedValue)
            throws TimeoutException, IllegalStateException {

        String answer = "";
        String ERR = "";

        int sleepIntvlMs = 1000;
        int maxRetry = 5;

        boolean isSet = false;

        /*
         * PI trigger distances/ranges are sent in mm. 1e-5 mm = 0.01 um.
         * This is much more realistic than requiring nm-level echo matching.
         */
        final double numericToleranceMm = 1e-5;

        CMMCore core = MMStudioInstance.getCore();
        long overallTic = System.currentTimeMillis();

        for (int i = 0; i < maxRetry && !isSet; i++) {
            try {
                PIStageLogger.info(String.format(
                        "PI serial attempt %d/%d | port=%s | cmd=%s | query=%s | expected=%.8f",
                        i + 1, maxRetry, port, msg, queryMsg, expectedValue));

                core.setSerialPortCommand(port, msg, "\n");

                core.setSerialPortCommand(port, "ERR?", "\n");
                ERR = core.getSerialPortAnswer(port, "\n");

                if (!ERR.equals("0")) {
                    String errMsg = String.format(
                            "Error code %s from ERR? after sending command %s",
                            ERR, msg);
                    PIStageLogger.severe(errMsg);
                    throw new PIControllerErrorException(errMsg);
                }

                core.setSerialPortCommand(port, queryMsg, "\n");
                answer = core.getSerialPortAnswer(port, "\n");

                PIStageLogger.info(String.format(
                        "Received PI answer %s from %s", answer, port));

                double value = Double.parseDouble(answer.split("=")[1].trim());
                double diff = Math.abs(value - expectedValue);

                isSet = diff <= numericToleranceMm;

                PIStageLogger.info(String.format(
                        "PI set/check | cmd=%s | value=%.8f | expected=%.8f | diff=%.8f | tolerance=%.8f | isSet=%s",
                        msg,
                        value,
                        expectedValue,
                        diff,
                        numericToleranceMm,
                        isSet));

                if (!isSet) {
                    throw new Exception(String.format(
                            "PI value mismatch for %s. Expected %.8f, got %.8f, diff %.8f",
                            msg, expectedValue, value, diff));
                }

            } catch (Exception e) {
                PIStageLogger.warning(String.format(
                        "PI serial retry | cmd=%s | attempt=%d/%d | reason=%s",
                        msg, i + 1, maxRetry, e.toString()));

                if (i >= maxRetry - 1) {
                    throw new TimeoutException(String.format(
                            "Failed to set PI serial cmd '%s' after %d tries in %d ms. Last answer=%s | last ERR=%s | last exception=%s",
                            msg,
                            maxRetry,
                            System.currentTimeMillis() - overallTic,
                            answer,
                            ERR,
                            e.getMessage()));
                }

                try {
                    Thread.sleep(sleepIntvlMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TimeoutException(
                            "Interrupted while retrying PI serial command " + msg);
                }
            }
        }

        if (!isSet) {
            throw new TimeoutException(String.format(
                    "Failed to verify PI serial cmd '%s' after %d tries. Last answer=%s | expected=%.8f | last ERR=%s",
                    msg,
                    maxRetry,
                    answer,
                    expectedValue,
                    ERR));
        }
    }

    // -------------------------------------------------------------------------
    // Generic serial retry helpers
    // -------------------------------------------------------------------------

    public static void sendSerialCommandRetry(String port, String msg)
            throws PISerialException {
        sendSerialCommandRetry(port, msg, "\n", 5);
    }

    public static void sendSerialCommandRetry(
            String port, String msg, String terminator, int maxRetry)
            throws PISerialException {

        boolean sendSerialSuccess = false;
        int intvlMs = 1000;
        int attempts = 0;

        while (!sendSerialSuccess && attempts <= maxRetry) {
            try {
                MMStudioInstance.getCore().setSerialPortCommand(
                        port, msg, terminator);
                sendSerialSuccess = true;

            } catch (Exception ex) {
                attempts++;

                int waitTime = attempts * attempts * intvlMs;

                PIStageLogger.warning(String.format(
                        "sendSerialCommandRetry failed for %s, attempt %d/%d, waiting %.1f s. Exception: %s",
                        msg,
                        attempts,
                        maxRetry,
                        waitTime / 1000.0,
                        ex.getMessage()));

                if (attempts > maxRetry) {
                    String errorMsg = String.format(
                            "sendSerialCommandRetry failed after %d attempts for %s with %s",
                            attempts, msg, ex.getMessage());
                    PIStageLogger.severe(errorMsg);
                    throw new PISerialException(errorMsg);
                }

                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PISerialException(
                            "Interrupted while sending serial command " + msg);
                }
            }
        }
    }

    public static String getSerialAnswerCommandRetry(String port)
            throws PISerialException {
        return getSerialAnswerCommandRetry(port, "\n", 5);
    }

    public static String getSerialAnswerCommandRetry(
            String port, String terminator, int maxRetry)
            throws PISerialException {

        boolean getSerialSuccess = false;
        int intvlMs = 1000;
        int attempts = 0;

        while (!getSerialSuccess && attempts <= maxRetry) {
            try {
                String ans = MMStudioInstance.getCore().getSerialPortAnswer(
                        port, terminator);
                getSerialSuccess = true;
                return ans;

            } catch (Exception ex) {
                attempts++;

                int waitTime = attempts * attempts * intvlMs;

                PIStageLogger.warning(String.format(
                        "getSerialAnswerCommandRetry failed, attempt %d/%d, waiting %.1f s. Exception: %s",
                        attempts,
                        maxRetry,
                        waitTime / 1000.0,
                        ex.getMessage()));

                if (attempts > maxRetry) {
                    String errorMsg = String.format(
                            "getSerialAnswerCommandRetry failed after %d attempts with %s",
                            attempts,
                            ex.getMessage());
                    PIStageLogger.severe(errorMsg);
                    throw new PISerialException(errorMsg);
                }

                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PISerialException(
                            "Interrupted while reading serial answer");
                }
            }
        }

        return "";
    }

    // -------------------------------------------------------------------------
    // Debug helpers
    // -------------------------------------------------------------------------

    /**
     * Query and log important PI trigger settings.
     *
     * @param port PI controller COM port
     * @return string array with queried settings
     */
    public static String[] viewTriggerSettings(String port) {

        String[] settings = new String[5];
        CMMCore core = MMStudioInstance.getCore();

        try {
            core.setSerialPortCommand(port, "VEL? 1", "\n");
            settings[0] = "speed," + core.getSerialPortAnswer(port, "\n");
            PIStageLogger.info(settings[0]);

            core.setSerialPortCommand(port, "POS? 1", "\n");
            settings[1] = "position," + core.getSerialPortAnswer(port, "\n");
            PIStageLogger.info(settings[1]);

            core.setSerialPortCommand(port, "CTO? 1 8", "\n");
            settings[2] = "start trigger," + core.getSerialPortAnswer(port, "\n");
            PIStageLogger.info(settings[2]);

            core.setSerialPortCommand(port, "CTO? 1 9", "\n");
            settings[3] = "end trigger," + core.getSerialPortAnswer(port, "\n");
            PIStageLogger.info(settings[3]);

            core.setSerialPortCommand(port, "CTO? 1 1", "\n");
            settings[4] = "trig dist," + core.getSerialPortAnswer(port, "\n");
            PIStageLogger.info(settings[4]);

        } catch (Exception e) {
            PIStageLogger.severe(
                    "Failed to get PI trigger settings with " + e.toString());
        }

        return settings;
    }

    // -------------------------------------------------------------------------
    // Local exception classes
    // -------------------------------------------------------------------------

    static class PIControllerErrorException extends Exception {

        public int errorCode;

        public PIControllerErrorException() {
        }

        public PIControllerErrorException(String message) {
            super(message + " [Unknown PI controller error code]");
        }

        public PIControllerErrorException(String message, int error) {
            super(message + String.format(
                    " [Error code %d in PI controller]", error));
            errorCode = error;
        }
    }

    static class PISerialException extends Exception {

        public PISerialException() {
        }

        public PISerialException(String message) {
            super(message);
        }
    }
}