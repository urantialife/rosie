package edu.umich.insoar;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import probcog.lcmtypes.perception_command_t;

import com.soartech.bolt.testing.ActionType;
import com.soartech.bolt.testing.ISpyScriptGenerator;
import com.soartech.bolt.testing.ParseScript;
import com.soartech.bolt.testing.Script;

import edu.umich.insoar.world.WMUtil;
import april.util.TimeUtil;
import lcm.lcm.LCM;
import lcm.lcm.LCMSubscriber;
import sml.Agent;
import sml.Agent.OutputEventInterface;
import sml.Agent.RunEventInterface;
import sml.Identifier;
import sml.WMElement;
import sml.smlRunEventId;

public class ISpyRunner implements OutputEventInterface {
	private SoarAgent soarAgent;
	private ChatFrame chatFrame;
	
	private ArrayList<String> findCommands;
	private int cmdIndex;
	
	private PrintWriter logWriter = null;
	private boolean started = false;
	
	private boolean prevPickup = false;
	private long commandTime = 0;
	private int numMoves = 0;
	private int numFailedPickups = 0;

	
	public ISpyRunner(SoarAgent soarAgent, ChatFrame chatFrame){
		this.soarAgent = soarAgent;
		this.chatFrame = chatFrame;
		
		for(String outputHandlerString : new String[]{ "pick-up", "put-down", "interaction-status", "find-object-result" }){
        	soarAgent.getAgent().AddOutputHandler(outputHandlerString, this, null);
		}
	}

	private void openLogfile(String logname){
		if(logWriter != null){
			logWriter.close();
			logWriter = null;
		}
		if(logname == "null"){
			return;
		}
		try{
			logWriter = new PrintWriter(logname, "UTF-8");
		} catch (FileNotFoundException e){
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}
	
		private static String[][] properties = new String[][]{
		new String[]{ "red", "orange", "yellow", "green", "blue", "purple" },
		new String[]{ "crescent", "square", "l-block", "arch", "triangle", "rectangle"},
//		new String[]{ "small", "medium", "large"},
		new String[]{ "light", "moderate", "heavy", "hot", "warm", "cold"},
		new String[]{ "cold-est", "hot-est", "heavy-est", "light-est", "heavy-est", "light-est"}
//		new String[]{ "hot", "warm", "cool", "cold"}
	};
	
//	private static String[][] properties = new String[][]{
//	new String[]{ "red", "green", "blue" },
//	new String[]{ "small", "medium", "large"},
//	new String[]{ "light", "moderate", "heavy"},
//	};
	
//	private static String[][] properties = new String[][]{
//		new String[]{ "green", "triangle", "cold", "heavy-est" }
//	};
	
	private void generateFindCommands(){
		findCommands = new ArrayList<String>();
		for(String[] propList : properties){
			for(String prop : propList){
				findCommands.add(String.format("Find the %s object", prop));
			}
		}
		Collections.shuffle(findCommands, new Random(TimeUtil.utime()));
		cmdIndex = 0;
	}

	private void startNewExperiment(String logfile){
		started = true;
		openLogfile(logfile);
		
		perception_command_t cmd = new perception_command_t();
		cmd.utime = InSoar.GetSoarTime();
		cmd.command = "LOAD_CLASSIFIERS=default";
        LCM.getSingleton().publish("PERCEPTION_COMMAND", cmd);
		
		generateFindCommands();
		sendNextCommand();
	}
	
	private void sendNextCommand(){
		if(started){
			if(cmdIndex > 0){
				// Record timing results
		    	logWriter.println(String.format("M %d", numMoves));
		    	logWriter.println(String.format("F %d", numFailedPickups));
		    	logWriter.println(String.format("T %d", (TimeUtil.utime() - commandTime)));
			}
			
			if(cmdIndex == findCommands.size()){
				started = false;
				logWriter.close();
				logWriter = null;
				return;
			}
			String cmd = findCommands.get(cmdIndex++);
			chatFrame.addMessage(cmd, ActionType.Mentor);
			chatFrame.sendSoarMessage(cmd);
			logWriter.println(String.format("C %s", cmd));

			numMoves = 0;
			numFailedPickups = 0;
			prevPickup = false;
			commandTime = TimeUtil.utime();
		}
	}
	
	public JMenu createMenu(){
		JMenu menu = new JMenu("ISpy");
		
		JMenuItem startBtn = new JMenuItem("Start");
		startBtn.addActionListener(new ActionListener(){
			@Override
			public void actionPerformed(ActionEvent arg0) {
        		String logfile = JOptionPane.showInputDialog(null, 
          			  "Enter the name of the log to create",
          			  "Log File",
          			  JOptionPane.QUESTION_MESSAGE);
        		startNewExperiment(logfile);
			}
		});
		menu.add(startBtn);
		return menu;
	}
	
    /*************************************************
     * outputEventHandler
     * Runs when the agent puts the appropriate command on the output link
     * send-training-label: sends a new training example to perception
     *************************************************/
    public void outputEventHandler(Object data, String agentName,
            String attributeName, WMElement wme)
    {
    	synchronized(this){
    		if (!(wme.IsJustAdded() && wme.IsIdentifier()))
            {
                return;
            }
    		Identifier id = wme.ConvertToIdentifier();
    		String att = wme.GetAttribute();
    		if(att.equals("interaction-status")){
    			handleInteractionStatus(id);
    		} else if(att.equals("find-object-result")){
    			handleFindObjectResults(id);
    		} else if(att.equals("pick-up")){
    			if(prevPickup){
    				numFailedPickups++;
    			}
    			prevPickup = true;
    		} else if(att.equals("put-down")){
    			if(prevPickup){
    				numMoves++;
    			}
    			prevPickup = false;
    		}
    	}
    }
    
    private void handleInteractionStatus(Identifier id){
    	String type = WMUtil.getValueOfAttribute(id, "status");
    	if(type != null && type.equals("idle")){
    		sendNextCommand();
    	}
    	id.CreateStringWME("status", "complete");
    }
    
    private void handleFindObjectResults(Identifier id){
    	if(logWriter == null){
    		id.CreateStringWME("status", "complete");
    		return;
    	}
    	String status = WMUtil.getValueOfAttribute(id, "status");
    	String count = WMUtil.getValueOfAttribute(id, "action-count");
    //	logWriter.println(String.format("C %s", count));
    	logWriter.println(String.format("S %s", status));
    	if(status.equals("success")){
    		Identifier objId = WMUtil.getIdentifierOfAttribute(id, "object");
    		String desc = "D ";
    		for(int index = 0; index < objId.GetNumberChildren(); index++){
                WMElement wme = objId.GetChild(index);
                String att = wme.GetAttribute();
                String val = wme.GetValueAsString();
                desc += att + "=" + val + " ";
            }
    		logWriter.println(desc);
    	}
    	id.CreateStringWME("status", "complete");
    }
}
