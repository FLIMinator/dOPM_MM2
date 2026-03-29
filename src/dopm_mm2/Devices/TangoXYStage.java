/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package dopm_mm2.Devices;

import dopm_mm2.util.MMStudioInstance;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mmcorej.CMMCore;

/** Static class to control Marzhauser XY stage with Tango, mostly for
 * triggering with the ASCII API.
 *
 * <p>This class contains:
 * <ul>
 *   <li>basic XY motion helpers</li>
 *   <li>Tango trigger configuration helpers</li>
 *   <li>serial send/query/verify utilities</li>
 * </ul>
 *
 * <p>Trigger-range usage is organised in three layers:
 * <ol>
 *   <li>read current trigger distance from Tango, then derive range</li>
 *   <li>derive trigger count from a supplied trigger distance</li>
 *   <li>program an explicit {@code !trigr start end nTriggers}</li>
 * </ol>
 *
 * <p>Mode-2 single-start triggering uses a special helper that programs a
 * single trigger at the start of the range.
 *
 * @author Leo Rowe-Brown
 *
 * REVISION (Layer 4):
 * - Preserved all original regex Matcher and Pattern logic in setAndCheckSerial.
 * - Added customWaitForPort(port) before serial queries to prevent buffer collisions.
 * - Improved checkSerialSet to ensure mathematical precision across multiple values.
 */
public class TangoXYStage {

    private static final Logger tangoXYLogger =
            Logger.getLogger(TangoXYStage.class.getName());

    // Constructor
    public void TangoXYStage() {
    }

    // -------------------------------------------------------------------------
    // Basic Tango serial helpers
    // -------------------------------------------------------------------------

    public static String getTangoErrorMsg(String port) throws Exception {
        try {
            MMStudioInstance.getCore().setSerialPortCommand(port, "err", "\r");
            return MMStudioInstance.getCore().getSerialPortAnswer(port, "\r");
        } catch (Exception e) {
            tangoXYLogger.severe(String.format(
                    "Failed to get Tango error with: %s", e.getMessage()));
            throw new Exception(e);
        }
    }

    /** Initialize Tango API units to um for X and Y.
     *
     * @param port Tango COM port
     * @throws TimeoutException if the command cannot be set and verified
     */
    public static void setTangoXyUnitsToUm(String port) throws TimeoutException {
        setAndCheckSerial(port, "!dim y 1", "?dim y", "1");
        setAndCheckSerial(port, "!dim x 1", "?dim x", "1");
    }

    /** Wait for the Tango controller on a given device to become ready by
     * querying its port.
     *
     * @param device Micro-Manager device name
     * @throws Exception if the device port cannot be queried or times out
     */
    private static void customWaitForStage(String device) throws Exception {
        String port = MMStudioInstance.getCore().getProperty(device, "Port");
        customWaitForPort(port);
    }

    /** Poll Tango status until the controller reports ready.
     *
     * <p>Uses {@code ?statusaxis} and waits for the idle response
     * {@code JJ--.-}.
     *
     * @param port Tango COM port
     * @throws Exception if Tango does not become ready before timeout
     */
    private static void customWaitForPort(String port) throws Exception {
        long timeout = 10000;
        int intvlMs = 10;
        int waitedMs = 0;

        // Initial query
        MMStudioInstance.getCore().setSerialPortCommand(port, "?statusaxis", "\r");
        String ans = MMStudioInstance.getCore().getSerialPortAnswer(port, "\r");

        while (!ans.equals("JJ--.-") && waitedMs < timeout) {
            Thread.sleep(intvlMs);
            MMStudioInstance.getCore().setSerialPortCommand(port, "?statusaxis", "\r");
            ans = MMStudioInstance.getCore().getSerialPortAnswer(port, "\r");
            waitedMs += intvlMs;
        }

        if (waitedMs >= timeout) {
            throw new TimeoutException(
                    "Timed out waiting for Tango stage (port: " + port + ")");
        }
    }

    // -------------------------------------------------------------------------
    // XY motion helpers
    // -------------------------------------------------------------------------

    /** Generic Tango single-axis move command using Micro-Manager XY stage API.
     *
     * @param device XY stage device name
     * @param position target position for the chosen axis in um
     * @param axis axis to move, x or y
     * @throws Exception if motion fails
     */
    public static void setAxisPosition(
            String device, double position, String axis) throws Exception {
        try {
            MMStudioInstance.getCore().waitForDevice(device);
            double posXStart = MMStudioInstance.getCore().getXPosition(device);
            double posYStart = MMStudioInstance.getCore().getYPosition(device);

            switch (axis) {
                case "x":
                    MMStudioInstance.getCore().setXYPosition(
                            device, position, posYStart);
                    tangoXYLogger.info(String.format(
                            "Set %s position to %.2f um", axis, position));
                    break;
                case "y":
                    MMStudioInstance.getCore().setXYPosition(
                            device, posXStart, position);
                    tangoXYLogger.info(String.format(
                            "Set %s position to %.2f um", axis, position));
                    break;
                default:
                    throw new Exception(String.format(
                            "%s is an invalid axis, use x or y", axis));
            }
        } catch (Exception e) {
            tangoXYLogger.severe(String.format(
                    "Failed to set %s %s to position %.2f um with: %s",
                    device, axis, position, e.getMessage()));
            throw new Exception(e);
        }
    }

    /** Wrap {@code setXYPosition} and wait for the device before issuing the move.
     *
     * @param device device name in Micro-Manager
     * @param x x position in um
     * @param y y position in um
     * @throws Exception if motion fails
     */
    public static void setXyPosition(
            String device, double x, double y) throws Exception {
        try {
            long start = System.currentTimeMillis();
            MMStudioInstance.getCore().waitForDevice(device);
            tangoXYLogger.info(String.format(
                    "waited %d ms for %s",
                    System.currentTimeMillis() - start, device));
            MMStudioInstance.getCore().setXYPosition(device, x, y);
            tangoXYLogger.info(String.format(
                    "Set position to (%.2f, %.2f) um (x,y)", x, y));
        } catch (Exception e) {
            tangoXYLogger.severe(String.format(
                    "Failed to set %s position (%.2f, %.2f) um with: %s",
                    device, x, y, e.getMessage()));
            throw new Exception(e);
        }
    }

    /** Set speed of both Tango axes in mm/s.
     *
     * @param device device name in Micro-Manager
     * @param speed speed in mm/s
     * @throws Exception if setting either axis speed fails
     */
    public static void setTangoAxisSpeed(String device, double speed)
            throws Exception {
        setTangoAxisSpeed(device, "x", speed);
        setTangoAxisSpeed(device, "y", speed);
    }

    /** Set speed of a specific Tango axis in mm/s.
     *
     * @param device device name in Micro-Manager
     * @param axis "x" or "y"
     * @param speed speed in mm/s
     * @throws Exception if the property cannot be set
     */
    public static void setTangoAxisSpeed(String device, String axis,
            double speed) throws Exception {
        switch (axis) {
            case "x":
                MMStudioInstance.getCore().setProperty(
                        device, "SpeedX [mm/s]", speed);
                break;
            case "y":
                MMStudioInstance.getCore().setProperty(
                        device, "SpeedY [mm/s]", speed);
                break;
            default:
                throw new IllegalArgumentException("Invalid axis, use x or y");
        }
    }

    /** Get current Tango X/Y speeds from Micro-Manager.
     *
     * @param device Micro-Manager device name
     * @return array containing [x speed, y speed]
     * @throws Exception if reading properties fails
     */
    public static double[] getTangoXySpeed(String device) throws Exception {
        double[] speeds = new double[2];
        speeds[0] = Double.parseDouble(
                MMStudioInstance.getCore().getProperty(device, "SpeedX [mm/s]"));
        speeds[1] = Double.parseDouble(
                MMStudioInstance.getCore().getProperty(device, "SpeedY [mm/s]"));
        return speeds;
    }

    /** Generic XY move command expressed in millimetres.
     *
     * <p>Input is supplied in mm and converted to um before sending through
     * the Micro-Manager XY stage API.
     *
     * @param device device name
     * @param position position array [x, y] in mm
     * @throws Exception if motion fails
     */
    public static void setTangoXYPositionMillim(
            String device, double[] position) throws Exception {
        double posX = position[0] * 1e3;
        double posY = position[1] * 1e3;
        try {
            MMStudioInstance.getCore().setXYPosition(device, posX, posY);
            tangoXYLogger.info(String.format(
                    "Set %s position to (%.4f,%.4f) mm", device, posX, posY));
        } catch (Exception e) {
            tangoXYLogger.severe(String.format(
                    "Failed to set %s to position (%.4f,%.4f) mm with: %s",
                    device, posX, posY, e.getMessage()));
            throw new Exception(e);
        }
    }

    // -------------------------------------------------------------------------
    // Tango trigger configuration helpers
    // -------------------------------------------------------------------------

    /** Set Tango trigger distance for a given axis.
     *
     * <p>The device adapter can reset dimensions while reading position, so this
     * helper first forces Tango units back to um before setting {@code trigd}.
     *
     * @param port COM port
     * @param axis axis to configure, x or y
     * @param triggerDistance trigger distance in um
     * @throws Exception if setting or verification fails
     */
    public static void setTangoTriggerDistance(
            String port, String axis, double triggerDistance) throws Exception {
        setTangoXyUnitsToUm(port);
        String msg = String.format("!trigd %s %.2f", axis, triggerDistance);
        String queryMsg = String.format("?trigd %s", axis);
        String expectedValue = String.format("%.2f", triggerDistance);
        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            tangoXYLogger.severe(
                    "Failed to set Tango trigger distance with " + e.getMessage());
            throw e;
        }
    }

    /** Set which Tango axis generates trigger output.
     *
     * @param port COM port
     * @param axis "x" or "y"
     * @throws Exception if setting or verification fails
     */
    public static void setTangoTriggerAxis(String port, String axis)
            throws Exception {
        String msg = String.format("!triga %s", axis);
        String queryMsg = "?triga";
        String expectedValue = axis;
        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            tangoXYLogger.severe(
                    "Failed to set Tango trigger axis with " + e.getMessage());
            throw e;
        }
    }

    /** Enable or disable Tango triggering.
     *
     * @param port COM port
     * @param trigOn 1 to enable, 0 to disable
     * @throws Exception if setting or verification fails
     */
    public static void setTangoTriggerEnable(
            String port, int trigOn) throws Exception {
        String msg = String.format("!trig %d", trigOn);
        String queryMsg = "?trig";
        String expectedValue = String.valueOf(trigOn);
        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            tangoXYLogger.severe(
                    "Failed to set Tango trigger state with " + e.getMessage());
            throw e;
        }
    }

    /** Set a Tango trigger range using the currently configured trigger distance.
     *
     * <p>This convenience overload reads {@code ?trigd axis} from Tango, then
     * delegates to the overload that derives the trigger count from that distance.
     *
     * @param port COM port
     * @param axis axis to trigger over, x or y
     * @param desiredTriggerRange desired scan range as [start, end]
     * @return actual programmed trigger range {startTrigger, endTrigger}
     * @throws Exception if reading {@code trigd} or setting the range fails
     */
    public static double[] setTangoTriggerRange(String port, String axis,
            double[] desiredTriggerRange) throws Exception {
        long start_ = System.currentTimeMillis();
        setTangoXyUnitsToUm(port);

        customWaitForPort(port);

        MMStudioInstance.getCore().setSerialPortCommand(
                port, "?trigd " + axis, "\r");

        double triggerDist = Double.parseDouble(
                MMStudioInstance.getCore().getSerialPortAnswer(port, "\r"));
        tangoXYLogger.info("got trigger distance as " + triggerDist);

        double actualTriggerRange[] = setTangoTriggerRange(
                port, axis, desiredTriggerRange, triggerDist);
        tangoXYLogger.info(String.format(
                "caluclated and set trigger intervals in %d",
                System.currentTimeMillis() - start_));
        return actualTriggerRange;
    }

    /** Set a Tango trigger range by deriving the trigger count from a supplied
     * trigger distance.
     *
     * <p>This is the main helper used by the runnable code.  
	 * For ordinary repeated triggering it derives an integer trigger count from
	 * {@code floor(rangeLength / triggerDist) + 1} so that Tango trigger positions
	 * include both the start and end of the programmed range
     *
     * <p>Special case: if the supplied trigger distance exceeds the full range
     * length, the code programs a single trigger at the range start. This is used
     * for start-triggered internal-clock operation.
     *
     * @param port COM port
     * @param axis axis to trigger over, x or y
     * @param desiredTriggerRange desired scan range as [start, end]
     * @param triggerDist trigger distance in um
     * @return actual programmed trigger range {startTrigger, endTrigger}
     * @throws Exception if validation or trigger programming fails
     */
    public static double[] setTangoTriggerRange(String port, String axis,
            double[] desiredTriggerRange, double triggerDist) throws Exception {

        double rangeLength = desiredTriggerRange[1] - desiredTriggerRange[0];
        if (triggerDist <= 0) {
            throw new IllegalArgumentException("triggerDist must be > 0");
        }

        /*
         * Important special case for mode 2 (start trigger + internal clock):
         * if the requested trigger distance is larger than the whole scan range,
         * the controller should emit only the first trigger at the start of the
         * scan.
         *
         * Converting that situation into nTriggers=1 and sending
         *     !trigr start end 1
         * is WRONG for this controller, because Tango normalises that to a
         * single trigger located at the END of the range, i.e. the reply becomes
         *     end end 1
         * which delays the starter-pistol trigger until scan completion.
         *
         * To preserve the intended "huge trigger distance" behaviour, explicitly
         * program a single trigger located at the range start.
         */
        if (triggerDist > rangeLength) {
            tangoXYLogger.info(String.format(
                    "Trigger distance %.2f um exceeds range length %.2f um; using single start trigger at %.2f um",
                    triggerDist, rangeLength, desiredTriggerRange[0]));
            return setTangoSingleStartTriggerAt(
                    port, axis, desiredTriggerRange[0]);
        }

        //int nTriggers = Math.max(1, (int) Math.floor(rangeLength / triggerDist)); I added an extra trigger - this could make global reset vulnerable at end of scan?
		int nTriggers = Math.max(1, (int) Math.floor(rangeLength / triggerDist) + 1);
        return setTangoTriggerRange(port, axis, desiredTriggerRange, nTriggers);
    } 
    /** Program a true single trigger located at the start of the requested range.
     *
     * <p>This helper is used for start-triggered internal-clock scans. It avoids
     * the controller behaviour where {@code !trigr start end 1} can collapse to a
     * single trigger placed at the end of the interval.
     *
     * @param port COM port
     * @param axis axis to trigger over, x or y
     * @param startTrigger start coordinate of the trigger in um
     * @return actual programmed trigger range {startTrigger, startTrigger}
     * @throws Exception if Tango cannot be programmed or verified
     */
    public static double[] setTangoSingleStartTriggerAt(String port, String axis,
            double startTrigger) throws Exception {
        String expectedValuesStr = String.format("%.2f %.2f %d",
                startTrigger, startTrigger, 1);
        String msg = String.format("!trigr %s", expectedValuesStr);
        String queryMsg = "?trigr";

        customWaitForPort(port);
        setAndCheckSerial(port, msg, queryMsg, expectedValuesStr);
        return new double[]{startTrigger, startTrigger};
    }

    /** Program a Tango trigger range using an explicit trigger count.
     *
     * <p>This is the lowest-level range helper: it directly issues
     * {@code !trigr start end nTriggers} and verifies the controller response.
     *
     * @param port COM port
     * @param axis axis to trigger over, x or y
     * @param desiredTriggerRange desired scan range as [start, end]
     * @param nTriggers explicit number of triggers to program
     * @return actual programmed trigger range {startTrigger, endTrigger}
     * @throws Exception if Tango cannot be programmed or verified
     */
    public static double[] setTangoTriggerRange(String port, String axis,
            double[] desiredTriggerRange, int nTriggers) throws Exception {
        int safeNTriggers = Math.max(1, nTriggers);
        double startTrigger = desiredTriggerRange[0];
        double endTrigger = desiredTriggerRange[1];

        String expectedValuesStr = String.format("%.2f %.2f %d",
                startTrigger, endTrigger, safeNTriggers);
        String msg = String.format("!trigr %s", expectedValuesStr);
        String queryMsg = "?trigr";

        customWaitForPort(port);
        setAndCheckSerial(port, msg, queryMsg, expectedValuesStr);
        return new double[]{startTrigger, endTrigger};
    }

    // -------------------------------------------------------------------------
    // Serial write / verify helper
    // -------------------------------------------------------------------------

    /** Set a serial value, query it back, and retry until it matches.
     *
     * <p>The sequence is:
     * <ol>
     *   <li>send the set command</li>
     *   <li>query Tango {@code err}</li>
     *   <li>query the configured value</li>
     *   <li>compare with the expected response</li>
     *   <li>retry if needed</li>
     * </ol>
     *
     * <p>The original regex logic for extracting axis information from the
     * message has been preserved exactly.
     *
     * @param port COM port
     * @param msg serial command used to set the value
     * @param queryMsg serial command used to verify the value
     * @param expectedValueStr expected response string
     * @throws TimeoutException if setting/verification fails after all retries
     * @throws IllegalStateException not explicitly thrown here, but preserved in
     * the method signature for compatibility
     */
    public static void setAndCheckSerial(
            String port, String msg, String queryMsg, String expectedValueStr)
            throws TimeoutException, IllegalStateException {

        Pattern p = Pattern.compile("[xy]");
        Matcher m = p.matcher(msg);
        String axis = "";
        while (m.find()) {
            axis = m.group(0);
        }

        String errCmd = "err";
        int sleepIntvlMs = 300;
        int maxRetry = 50;
        boolean isSet = false;
        String answer = "";
        String ERR = "";
        CMMCore core = MMStudioInstance.getCore();
        long overallTic = System.currentTimeMillis();

        for (int i = 0; i < maxRetry && !isSet; i++) {
            long attemptTic = System.currentTimeMillis();
            try {
                tangoXYLogger.info(String.format(
                        "Tango serial attempt %d/%d | port=%s | cmd=%s | query=%s | expected=%s",
                        i + 1, maxRetry, port, msg, queryMsg, expectedValueStr));

                long cmdTic = System.currentTimeMillis();
                core.setSerialPortCommand(port, msg, "\r");
                tangoXYLogger.info(String.format(
                        "TIMING | TangoSerial | setCommand | %s | %d ms",
                        msg, System.currentTimeMillis() - cmdTic));

                long errTic = System.currentTimeMillis();
                core.setSerialPortCommand(port, errCmd, "\r");
                ERR = core.getSerialPortAnswer(port, "\r");
                tangoXYLogger.info(String.format(
                        "TIMING | TangoSerial | errQuery | %s | %d ms | err=%s",
                        msg, System.currentTimeMillis() - errTic, ERR));
                if (!ERR.equals("0")) {
                    throw new Exception(String.format(
                            "Error code %s in Tango from err after command %s",
                            ERR, msg));
                }

                long queryTic = System.currentTimeMillis();
                core.setSerialPortCommand(port, queryMsg, "\r");
                answer = core.getSerialPortAnswer(port, "\r");
                tangoXYLogger.info(String.format(
                        "TIMING | TangoSerial | verifyQuery | %s | %d ms | answer=%s",
                        msg, System.currentTimeMillis() - queryTic, answer));

                isSet = checkSerialSet(answer, expectedValueStr);
                if (!isSet) {
                    throw new Exception(String.format(
                            "set values =/= expected. Expected: %s Answer: %s",
                            expectedValueStr, answer));
                }

                tangoXYLogger.info(String.format(
                        "Tango serial success | cmd=%s | axis=%s | attempt=%d | elapsed=%d ms",
                        msg, axis, i + 1,
                        System.currentTimeMillis() - attemptTic));
            } catch (Exception e) {
                tangoXYLogger.warning(String.format(
                        "Tango serial retry | cmd=%s | attempt=%d/%d | elapsed=%d ms | reason=%s",
                        msg, i + 1, maxRetry,
                        System.currentTimeMillis() - attemptTic, e.toString()));
                if (i >= maxRetry - 1) {
                    throw new TimeoutException(String.format(
                            "Failed to set %s after %d tries in %d ms. Last err=%s | last answer=%s | last ERR=%s",
                            msg, maxRetry,
                            System.currentTimeMillis() - overallTic,
                            e.getMessage(), answer, ERR));
                }
                try {
                    Thread.sleep(sleepIntvlMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Serial response comparison helpers
    // -------------------------------------------------------------------------

    /** Compare Tango serial response and expected values using space-separated
     * tokens.
     *
     * @param answer answer from a {@code ?} query, e.g. {@code "1 1"}
     * @param expectedValues expected value string, e.g. {@code "1 1"}
     * @return true if all values match
     * @throws IndexOutOfBoundsException if the token counts differ
     */
    public static boolean checkSerialSet(String answer, String expectedValues) {
        return checkSerialSet(answer.split(" "), expectedValues.split(" "));
    }

    /** Compare Tango serial response and expected values token by token.
     *
     * <p>Each token is first compared numerically when possible; otherwise it is
     * compared as a case-insensitive string.
     *
     * @param answers answer tokens
     * @param expectedValues expected tokens
     * @return true if all values match
     * @throws IndexOutOfBoundsException if the token counts differ
     */
    public static boolean checkSerialSet(String[] answers, String[] expectedValues)
            throws IndexOutOfBoundsException {

        boolean allMatch = true;

        if (expectedValues.length != answers.length) {
            throw new IndexOutOfBoundsException(String.format(
                    "Serial answer has %d values but expected %d",
                    answers.length, expectedValues.length));
        }

        for (int n = 0; n < expectedValues.length; n++) {
            boolean thisMatch;
            try {
                Double value = Double.valueOf(answers[n]);
                Double evalueDouble = Double.valueOf(expectedValues[n]);
                thisMatch = Math.abs(value - evalueDouble) < 1e-7;
            } catch (NumberFormatException ne) {
                thisMatch = answers[n].trim().equalsIgnoreCase(
                        expectedValues[n].trim());
            }
            allMatch = allMatch && thisMatch;
        }
        return allMatch;
    }
}