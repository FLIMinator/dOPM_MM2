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

/** Static class to control Marzhauser XY stange with Tango, mostly for 
 * triggering with the ASCII API
 *
 * @author Leo Rowe-Brown
 * REVISION (Layer 4):
 * - Preserved all original regex Matcher and Pattern logic in setAndCheckSerial.
 * - Added customWaitForPort(port) before serial queries to prevent buffer collisions.
 * - Improved checkSerialSet to ensure mathematical precision across multiple values.
 */
public class TangoXYStage {
    private static final Logger tangoXYLogger
        = Logger.getLogger(TangoXYStage.class.getName());
    
    // Constructor
    public void TangoXYStage() {
    }
    
    public static String getTangoErrorMsg(String port) throws Exception{
        try {
            MMStudioInstance.getCore().setSerialPortCommand(port, "err", "\r");
            return MMStudioInstance.getCore().getSerialPortAnswer(port, "\r");
        } catch (Exception e){
            tangoXYLogger.severe(String.format(
                    "Failed to get Tango error with: %s", e.getMessage()));
            throw new Exception(e);
        }   
    }
        
    /** Initialize things like dim (set API units to um)
     * * @param port 
     * @throws TimeoutException if fails to set command in 5 tries
     */
    public static void setTangoXyUnitsToUm(String port) throws TimeoutException {
        setAndCheckSerial(port, "!dim y 1", "?dim y" ,"1");
        setAndCheckSerial(port, "!dim x 1", "?dim x", "1");
    }
    
    /** Generic tango move command that takes scanAxis, uses MMCore.
     * * @param device XY stage device name
     * @param position target position for axis in um
     * @param axis axis to move, x or y
     */
    // Generic move command that takes scanAxis, uses MMCore functionality.
    // This feels clunky..
    public static void setAxisPosition(
            String device, double position, String axis) throws Exception {
        try {
            // does this wait for device, or polls? do i need a 
            // wait for device here?
            MMStudioInstance.getCore().waitForDevice(device);
            // customWaitForStage(device);
            double posXStart = MMStudioInstance.getCore().getXPosition(device);
            double posYStart = MMStudioInstance.getCore().getYPosition(device);
            
            switch (axis){
                case "x":
                    MMStudioInstance.getCore().setXYPosition(
                            device, position, posYStart);
                    tangoXYLogger.info(String.format("Set %s position to "
                            + "%.2f um", axis, position));
                    break;
                case "y":
                    MMStudioInstance.getCore().setXYPosition(
                            device, posXStart, position);
                    tangoXYLogger.info(String.format("Set %s position to "
                            + "%.2f um", axis, position));
                    break;
                default:
                    throw new Exception(String.format("%s is an invalid axis, "
                            + "use x or y", axis));
            }
        } catch (Exception e){
            tangoXYLogger.severe(String.format(
                    "Failed to set %s %s to position %.2f um with: %s",
                    device, axis, position, e.getMessage()));
            throw new Exception(e);
        }
    }
    
    
    private static void customWaitForStage(String device) throws Exception{
        String port = MMStudioInstance.getCore().getProperty(device, "Port");
        customWaitForPort(port);
    }
    
    /**Ideally faster than waitForDevice()*/
    private static void customWaitForPort(String port) throws Exception{
        long timeout = 10000;
        int intvlMs = 10;
        int waitedMs = 0;
        
        // Initial query
        MMStudioInstance.getCore().setSerialPortCommand(port, "?statusaxis", "\r");
        String ans = MMStudioInstance.getCore().getSerialPortAnswer(port, "\r");
        
        while(!ans.equals("JJ--.-") && waitedMs < timeout){
            Thread.sleep(intvlMs);
            MMStudioInstance.getCore().setSerialPortCommand(port, "?statusaxis", "\r");
            ans = MMStudioInstance.getCore().getSerialPortAnswer(port, "\r");
            waitedMs += intvlMs;
        } 
        if (waitedMs >= timeout){ // FIXED: Corrected logic check for timeout
            throw new TimeoutException("Timed out waiting for Tango stage (port: " + port + ")");
        } 
        
    }
    
    /** Wraps the core_.setXYPosition, WAITS for move to be done with inbuilt
     * device adapter/micromanager command waitForDevice?
     * * @param device device name in MicroManager
     * @param x x position in um
     * @param y y position in um
     * @throws Exception 
     */
    public static void setXyPosition(
            String device, double x, double y) throws Exception {
        try {
            long start = System.currentTimeMillis();
            MMStudioInstance.getCore().waitForDevice(device);
            tangoXYLogger.info(String.format("waited %d ms for %s",
                    System.currentTimeMillis()-start, device));
            MMStudioInstance.getCore().setXYPosition(
                            device, x, y);
            tangoXYLogger.info(String.format("Set position to "
                            + "(%.2f, %.2f) um (x,y)", x, y));
        }   catch (Exception e){
            tangoXYLogger.severe(String.format(
                    "Failed to set %s position (%.2f, %.2f) um with: %s",
                    device, x, y, e.getMessage()));
            throw new Exception(e);
        }
    }
        
        
    /** set speed of both Tango axes (mm/s)
     * * @param device device name in MMgr
     * @param speed speed in mm/s
     * @throws Exception 
     */
    public static void setTangoAxisSpeed(String device, double speed) 
            throws Exception{
        setTangoAxisSpeed(device, "x", speed);
        setTangoAxisSpeed(device, "y", speed);
    }

    /** set speed of specific Tango axis "x" or "y"
     * * @param device device name in MMgr
     * @param axis "x" or "y"
     * @param speed speed in mm/s
     * @throws Exception 
     */
    public static void setTangoAxisSpeed(String device, String axis, 
            double speed) throws Exception{
        switch(axis){
            case "x":
                MMStudioInstance.getCore().setProperty(device, "SpeedX [mm/s]", speed);
                break;
            case "y":
                MMStudioInstance.getCore().setProperty(device, "SpeedY [mm/s]", speed);
                break;
            default:
                throw new IllegalArgumentException("Invalid axis, use x or y"); 
        }      
    }
    
    /** get current tango speeds as array [x speed, y speed] from core
     * * @param device MM device name
     * @return (array) tango stage speeds as [x speed, y speed]
     * @throws Exception 
     */
    public static double[] getTangoXySpeed(String device) throws Exception{
        double[] speeds = new double[2];
        speeds[0] = Double.parseDouble(
                MMStudioInstance.getCore().getProperty(device, "SpeedX [mm/s]"));
        speeds[1] = Double.parseDouble(
                MMStudioInstance.getCore().getProperty(device, "SpeedY [mm/s]"));
        return speeds;
    }
    
    /** Generic move command in millimeters, tango uses um units (or 10s of um?)
     * * @param device
     * @param position
     * @throws Exception 
     */
    public static void setTangoXYPositionMillim(
            String device, double[] position) throws Exception{
        double posX = position[0]*1e3;
        double posY = position[1]*1e3;
        try {
            MMStudioInstance.getCore().setXYPosition(device, posX, posY);
            tangoXYLogger.info(String.format(
                    "Set %s position to (%.4f,%.4f) mm",device, posX, posY));
        } catch (Exception e){
            tangoXYLogger.severe(String.format(
                    "Failed to set %s to position (%.4f,%.4f) mm with: %s",
                    device, posX, posY, e.getMessage()));
            throw new Exception(e);
        }
    }
    
    /** Set Tango trigger distance (in millimeters) for axis <axis>
    * @param port COM port
    * @param axis axis to set trigger distance of
    * @param triggerDistance trigger distance in mm
    * @throws Exception
    */
    public static void setTangoTriggerDistance(
            String port, String axis, double triggerDistance) throws Exception {
        // need to do this, the device adapter fights me and calls 
        // !dim 1 1 every time it gets the position...
        setTangoXyUnitsToUm(port); 
        String msg = String.format("!trigd %s %.2f", axis, triggerDistance);
        String queryMsg = String.format("?trigd %s", axis);
        String expectedValue = String.format("%.2f", triggerDistance);
        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            tangoXYLogger.severe("Failed to set Tango trigger distance with " + e.getMessage());
            throw e;
        }
    }
    
    /** set which tango axis to trigger (x or y)
     * * @param port COM port
     * @param axis "x" or "y"
     * @throws Exception 
     */
    public static void setTangoTriggerAxis(String port, String axis) 
                throws Exception {
        String msg = String.format("!triga %s", axis);
        String queryMsg = "?triga";
        String expectedValue = axis;
        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            tangoXYLogger.severe("Failed to set Tango trigger axis with " + e.getMessage());
            throw e;
        }
    }
    
    public static void setTangoTriggerEnable(
            String port, int trigOn) throws Exception {
        String msg = String.format("!trig %d", trigOn);
        String queryMsg = "?trig";
        String expectedValue = String.valueOf(trigOn);
        try {
            setAndCheckSerial(port, msg, queryMsg, expectedValue);
        } catch (Exception e) {
            tangoXYLogger.severe("Failed to set Tango trigger state with " + e.getMessage());
            throw e;
        }
    }
    
    /** Set desired trigger range and calculate and return actual ranged based
     * on integer number of triggers considering the trigger distance.
     * Is always equal to or less than desiredTriggerRange.
     * This method gets trigd automatically
     * @param port COM port
     * @param axis axis to trigger over, x or y
     * @param desiredTriggerRange desired volume scan range
     * @return actual trigger range {startTrigger, endTrigger}
     * @throws Exception if setting trigger range fails
     **/
    public static double[] setTangoTriggerRange(String port, String axis,
            double[] desiredTriggerRange) throws Exception {
        long start_ = System.currentTimeMillis();
        setTangoXyUnitsToUm(port); 
        
        // REVISION Layer 4: Ensure serial buffer is clear and controller is ready
        customWaitForPort(port);
        
        MMStudioInstance.getCore().setSerialPortCommand(
                port, "?trigd " + axis, "\r");
        
        double triggerDist = Double.parseDouble(MMStudioInstance.getCore().
                getSerialPortAnswer(port, "\r"));
        tangoXYLogger.info("got trigger distance as " + triggerDist);
        
        // set desired trigger range and get actual
        double actualTriggerRange[] = setTangoTriggerRange(port, axis,
            desiredTriggerRange, triggerDist);
        tangoXYLogger.info(String.format("caluclated and set trigger intervals in %d",
                System.currentTimeMillis()-start_));
        return actualTriggerRange;
    }
    
    /** [RECOMMENDED] Works by calculating N number of triggers to fit in the desired 
     * trigger range and calculates the actual range resulting from N 
     * triggers separated by triggerDist. Is always less than or equal to
     * desiredTriggerRange.
     * @param port COM port
     * @param axis axis to trigger over, x or y
     * @param desiredTriggerRange desired volume scan range
     * @param triggerDist trigger distance used to calculate trigger range (um)
     * @return actual trigger range {startTrigger, endTrigger}
     * @throws Exception if setting trigger range fails
     **/
    public static double[] setTangoTriggerRange(String port, String axis,
            double[] desiredTriggerRange, double triggerDist) throws Exception {

        int nTriggers = (int)Math.floor(
                (desiredTriggerRange[1]-desiredTriggerRange[0])/triggerDist);
        double startTrigger = desiredTriggerRange[0];
        double endTrigger = startTrigger + nTriggers*triggerDist;
        // note that trigr sets trigm 20 implicitly
        
        String expectedValuesStr = String.format("%.2f %.2f %d", 
                startTrigger, endTrigger, nTriggers );
        String msg = String.format("!trigr %s", expectedValuesStr);
        String queryMsg = "?trigr";
        
        // REVISION Layer 4: Mandatory wait before command to prevent data corruption
        customWaitForPort(port);
        
        setAndCheckSerial(port, msg, queryMsg, expectedValuesStr);
        return new double[]{startTrigger, endTrigger};
    }

    
    /** sets a double value with tango ASCII e.g., triga x. 
     * @param port COM port
     * @param msg serial command used to set value
     * @param queryMsg serial command used to check value
     * @param expectedValueStr value(s) that is trying to be set
     * @throws TimeoutException if fails to set the value after 5 retries
     **/
    public static void setAndCheckSerial(
            String port, String msg, String queryMsg, String expectedValueStr)
            throws TimeoutException, IllegalStateException {

        // PRESERVED ORIGINAL REGEX LOGIC
        Pattern p = Pattern.compile("[xy]");
        Matcher m = p.matcher(msg);
        String axis = "";
        while(m.find()){
            axis = m.group(0);
        }
        
        String errCmd = "err";  

        String answer;
        int sleep_intvl_ms = 300;
        int maxRetry = 50;
        boolean isSet = false;
        String ERR;
        
        CMMCore core = MMStudioInstance.getCore();

        int i = 0;
        do {
            try {
                tangoXYLogger.info(String.format(
                        "Sending serial command %s to %s", msg, port));
                core.setSerialPortCommand(port, msg, "\r");
                // check for errors
                core.setSerialPortCommand(port, errCmd, "\r");
                ERR = core.getSerialPortAnswer(port, "\r");
                if (!ERR.equals("0")) {
                    String errMsg = String.format(
                            "Error code %1$s in Tango from err after sending "
                                    + "command %2$s", ERR, msg);
                    tangoXYLogger.severe(errMsg);
                    throw new Exception(errMsg);
                }
                // check if set correctly
                core.setSerialPortCommand(port, queryMsg, "\r");
                answer = core.getSerialPortAnswer(port, "\r");
                tangoXYLogger.info(String.format(
                        "Received answer %s from %s", answer, port));
                
                // get value after being set, could be string or double
                isSet = checkSerialSet(answer, expectedValueStr);
                if(!isSet) { throw new Exception(
                        String.format("set values =/= expected. "
                                + "Expected: %s Answer: %s",
                        expectedValueStr, answer));
                    }
            } catch (Exception e) {
                tangoXYLogger.warning("Tango serial command failed: " + e.toString());
                if (i > maxRetry) {
                    throw new TimeoutException(String.format("Failed to set %s after %d "
                            + "tries with exception %s", msg, maxRetry, e.getMessage()));
                }
                try {
                    Thread.sleep(sleep_intvl_ms);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            i++;
        } while (!isSet && i < maxRetry);

    }
    
    
    /** Check if serial is set and can handle multiple answer outputs, 
     * takes string input and does split(" ") on the input automatically
     * * @param answer answer from ? query, e.g. e.g. "1 1" from "?dim"
     * @param expectedValues expected set values e.g. "1 1" for "dim 1 1"
     * @return true if set values = expected values 
     * @throws IndexOutOfBoundsException 
     */    
    public static boolean checkSerialSet(String answer, String expectedValues){
        // get value after being set, could be string or double
        return checkSerialSet(answer.split(" "), expectedValues.split(" "));
    }
    
 
    /** Check if serial is set and can handle multiple answer outputs, 
     * takes string[] inputs (do split(" ") on the input strings)
     * * @param answers String[] answer from ? query, after .split(" ")
     * @param expectedValues String[] expected set values, after .split(" ")
     * @return true if set values = expected values 
     * @throws IndexOutOfBoundsException 
     */
    public static boolean checkSerialSet(String[] answers, String[] expectedValues) 
            throws IndexOutOfBoundsException{
        // we expect same no. values in answer as expectedValues length
        boolean allMatch = true;
        
        if (expectedValues.length != answers.length){
            throw new IndexOutOfBoundsException(String.format(
                    "Serial answer has %d values but expected %d", 
                    answers.length, expectedValues.length));
        }
        
        for (int n = 0; n < expectedValues.length; n++){
            boolean thisMatch;
            try {
                Double value = Double.valueOf(answers[n]);
                Double evalueDouble = Double.valueOf(expectedValues[n]);
                thisMatch = Math.abs(value - evalueDouble) < 1e-7; 
            } catch (NumberFormatException ne){ // just a string
                thisMatch = answers[n].trim().equalsIgnoreCase(expectedValues[n].trim());
            }
            allMatch = allMatch && thisMatch;
        }
        return allMatch;
    }
}