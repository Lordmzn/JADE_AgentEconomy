import jade.core.Agent;

import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.StringBuilder;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;


public class EconomicAgent extends Agent {
	// Delay between each bid proposal
	private final static int DELAY = 2000;
	
	// The quantities of goods owned by the agent
	public enum GoodType {
 		BREAD, GRAIN, LAND;
 	}
	private Map<GoodType, Integer> property;

	// Coefficients of linear equation of production: outputs = inputs
	// c1 * q1 + c2 * q2 = c1 * q1 + c2 * q2  (in case of GoodType.values().length = 2)
	private int[] coefficients;
	
	// Prices: for each good, how many others are asked for
	private Map<GoodType, Map<GoodType, Integer>> prices; 

	// The list of known seller agents
	private AID[] sellerAgents;

	// Log file
	private PrintWriter data_file;
	private EcononomicAgentGui gui;

	// Put agent initializations here
	protected void setup() {
		int nGoods = GoodType.values().length;
		property =  new HashMap<GoodType,Integer>();
		coefficients = new int[2 * nGoods];
		prices = new HashMap<GoodType, Map<GoodType, Integer>>();
		// init
		for (GoodType g : GoodType.values()) {
			property.put(g, 0);
			prices.put(g, new HashMap<GoodType, Integer>());
			for (GoodType go : GoodType.values()) {
				prices.get(g).put(go, 0);
			}
		}

		// Register the good-selling service in the yellow pages
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		ServiceDescription sd = new ServiceDescription();
		sd.setType("good-selling");
		sd.setName("JADE-economy");
		dfd.addServices(sd);
		try {
			DFService.register(this, dfd);
		}
		catch (FIPAException fe) {
			fe.printStackTrace();
		}

		// Open the gui
		gui = new EcononomicAgentGui();
		gui.showGui();

		// Add a TickerBehaviour that schedules a request to seller agents every ten seconds
		addBehaviour(new TickerBehaviour(this, DELAY) {
			protected void onTick() {
				// Update the list of seller agents
				DFAgentDescription template = new DFAgentDescription();
				ServiceDescription sd = new ServiceDescription();
				sd.setType("good-selling");
				template.addServices(sd);
				try {
					DFAgentDescription[] result = DFService.search(myAgent, template); 
					System.out.println("Found the following seller agents:");
					List<AID> temp = new ArrayList<AID>(); // I cannot sell to myself
					for (DFAgentDescription res : result) {
						if (!res.getName().equals(getAID().getName())) {
							temp.add(res.getName());
							System.out.println(temp.get(temp.size() - 1).getName());
						}
					}
					sellerAgents = new AID[temp.size()];
					temp.toArray(sellerAgents);
				}
				catch (FIPAException fe) {
					fe.printStackTrace();
				}

				// Perform the request
				myAgent.addBehaviour(new BuySomething());
			}
		} );

		// open log or die
		try {
			data_file = new PrintWriter(new BufferedWriter(new FileWriter("agent_" + getAID().getLocalName() + "_log.txt")));
		} 
		catch (IOException e) {
			e.printStackTrace();
			doDelete();
			return;
		}
		// Log goods
		StringBuilder sb = new StringBuilder();
		for (GoodType n : GoodType.values()) { 
    		sb.append("\t").append(n);
		}
		data_file.println(sb.toString());

		// Retrieve arguments, decode them and log the production rule to screen
		Object[] args = getArguments();
		if (args != null && args.length == 3*nGoods) {
			
			String prodRule = "Production rule is: ";
			int i = 0;
			for (; i < 2*nGoods; i++) {
				coefficients[i] = Integer.valueOf(args[i].toString());
				prodRule += " + " + coefficients[i] + " * " + GoodType.values()[i%nGoods];
				if (i == nGoods - 1) {
					prodRule += " =";
				}
			}
			System.out.println(prodRule);

			// we use material needed for production as prices of what we can produce (greedy approx)
			String prices_str = "Initial prices:\n";
			for (int j = 0; j < nGoods; j++) {
				if (coefficients[j] > 0) {
					prices_str += GoodType.values()[j] + ": ";
					for (int k = 0; k < nGoods; k++) {
						prices.get(GoodType.values()[j]).put(GoodType.values()[k], coefficients[k+nGoods]);
						if (coefficients[k+nGoods] > 0) {
							prices_str += coefficients[k+nGoods] + " " + GoodType.values()[k] + " + ";
						}
					}
					prices_str += "\n";
				} 
			}
			System.out.println(prices_str);
			
			String portfolio = "Initial portfolio:";
			for (GoodType g : GoodType.values()) {
				property.put(g, Integer.valueOf(args[i].toString()));
				portfolio += " (" + g + ": " + property.get(g) + ")";
				i++;
			}
			System.out.println(portfolio);
		}
		else {
			// Make the agent terminate
			System.out.println("Please write 3*N numbers");
			System.out.println("Production rule coeff cX e.g. N=2, [c1*q1 + c2*q2 = c1*q1 + c2*q2]");
			System.out.println("Initial stock e.g. N=2, [q1,q2]");
			doDelete();
		}
		logProperty();

		// do a first round of production
		produceGood();

		// Add the behaviour to serve queries from buyer agents
		addBehaviour(new ServeIncomingRequestsToBuyGoods());
		// Add the behaviour to actually sell the goods
		addBehaviour(new SellGoods());

	}

	// Put agent clean-up operations here
	protected void takeDown() {
		// Deregister from the yellow pages
		try {
			DFService.deregister(this);
		}
		catch (FIPAException fe) {
			fe.printStackTrace();
		}
		// Close the log
		data_file.close();
		// Close the GUI
		gui.dispose();
		// Printout a dismissal message
		System.out.println("Economic agent "+getAID().getName()+" terminating.");
	}

	/**
	 * Inner class ServeIncomingRequestsToBuyGoods.
	 * This is the behaviour used to serve incoming requests for offer from buyer agents.
	 * If the requested good is in the local property the seller agent replies 
	 * with a PROPOSE message specifying the prices. Otherwise a REFUSE message is
	 * sent back.
	 **/
	private class ServeIncomingRequestsToBuyGoods extends CyclicBehaviour {
		public void action() {
			MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
			ACLMessage msg = myAgent.receive(mt);
			if (msg != null) {
				// CFP Message received. Process it
				String good_str = msg.getContent(); // this is one of the goods
				ACLMessage reply = msg.createReply();

				// translate title into good_index
				GoodType good = GoodType.valueOf(good_str);
				if (property.get(good) > 0) {
					// The requested good is available for sale. Reply with the price
					reply.setPerformative(ACLMessage.PROPOSE);
					reply.setContent(Arrays.toString(prices.get(good).values().toArray(new Integer[0])));
				}
				else {
					// The requested good is NOT available for sale.
					reply.setPerformative(ACLMessage.REFUSE);
					reply.setContent("not-available");
				}
				myAgent.send(reply);
			}
			else {
				block();
			}
		}
	}  // End of inner class ServeIncomingRequestsToBuyGoods

	/**
	 * Inner class SellGoods.
	 * This is the behaviour used by seller agents to serve incoming 
	 * offer acceptances (i.e. purchase orders) from buyer agents.
	 * The seller agent removes the purchased good from its property 
	 * and replies with an INFORM message to notify the buyer that the
	 * purchase has been successfully completed.
	 **/
	private class SellGoods extends CyclicBehaviour {
		public void action() {
			MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
			ACLMessage msg = myAgent.receive(mt);
			if (msg != null) {
				// ACCEPT_PROPOSAL Message received. Process it
				String good_str = msg.getContent();
				ACLMessage reply = msg.createReply();

				// translate good_str into good.
				GoodType good = GoodType.valueOf(good_str);
				if (property.get(good) > 0) {
					soldGood(good); // we sold good good, very good!
					reply.setPerformative(ACLMessage.INFORM);
					System.out.println(good+" sold to agent "+msg.getSender().getLocalName()+" for "+prices.get(good));
				}
				else {
					// The requested good has been sold to another buyer in the meanwhile .
					reply.setPerformative(ACLMessage.FAILURE);
					reply.setContent("not-available");
				}
				myAgent.send(reply);
			}
			else {
				block();
			}
		}
	}  // End of inner class SellGoods

	/**
	 * Inner class BuySomething.
	 * This is the behaviour used when agents request seller agents the target good.
	 * which target? it takes the first n_goods coefficients; for those that are zero
	 * it makes an offer
	 **/
	private class BuySomething extends Behaviour {
		private AID bestSeller; // The agent who provides the best offer 
		private int bestPrice = 0;  // The best offered price
		private int[] bestPrices;
		private int repliesCnt = 0; // The counter of replies from seller agents
		private MessageTemplate mt; // The template to receive replies
		private int step = 0;

		private GoodType targetGood;

		public void onStart() {
			targetGood = GoodType.values()[0];
			List<GoodType> targetGoods = new ArrayList<GoodType>();
			int i = 0;
			for (GoodType g : GoodType.values()) {
				// if I can't produce it but I need it to produce and I don't have many
				if (coefficients[i] == 0 && property.get(g) < property.get(targetGood)) {
					targetGood = g;
				}
				i++;
			}
			System.out.println(getAID().getLocalName()+" wants "+targetGood);
		}

		public void action() {
			switch (step) {
			case 0:
				// Send the cfp to all sellers
				ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
				for (int i = 0; i < sellerAgents.length; ++i) {
					cfp.addReceiver(sellerAgents[i]);
				} 
				cfp.setContent(targetGood.name());
				cfp.setConversationId("good-trade");
				cfp.setReplyWith("cfp"+System.currentTimeMillis()); // Unique value
				myAgent.send(cfp);
				// Prepare the template to get proposals
				mt = MessageTemplate.and(MessageTemplate.MatchConversationId("good-trade"),
						MessageTemplate.MatchInReplyTo(cfp.getReplyWith()));
				step = 1;
				break;
			case 1:
				// Receive all proposals/refusals from seller agents
				ACLMessage reply = myAgent.receive(mt);
				if (reply != null) {
					// Reply received
					if (reply.getPerformative() == ACLMessage.PROPOSE) {
						// This is an offer 
						String[] strings = reply.getContent().replace("[", "").replace("]", "").split(", ");
    					int total_price = 0; // disregard my property distribution
    					for (int i = 0; i < strings.length; i++) {
      						total_price += Integer.parseInt(strings[i]);
    					}
						if (bestSeller == null || total_price < bestPrice) {
							// This is the best offer at present: keep it
							bestPrice = total_price;
							bestPrices = new int[strings.length];
    						for (int i = 0; i < strings.length; i++) {
      							bestPrices[i] = Integer.parseInt(strings[i]);
    						}
							bestSeller = reply.getSender();
						}
					}
					repliesCnt++;
					if (repliesCnt >= sellerAgents.length) {
						// We received all replies
						step = 2; 
					}
				}
				else {
					block();
				}
				break;
			case 2:
				// Send the purchase order to the seller that provided the best offer
				ACLMessage order = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
				order.addReceiver(bestSeller);
				order.setContent(targetGood.name());
				order.setConversationId("good-trade");
				order.setReplyWith("order"+System.currentTimeMillis());
				myAgent.send(order);
				// Prepare the template to get the purchase order reply
				mt = MessageTemplate.and(MessageTemplate.MatchConversationId("good-trade"),
						MessageTemplate.MatchInReplyTo(order.getReplyWith()));
				step = 3;
				break;
			case 3:      
				// Receive the purchase order reply
				reply = myAgent.receive(mt);
				if (reply != null) {
					// Purchase order reply received
					if (reply.getPerformative() == ACLMessage.INFORM) {
						// Purchase successful. We can terminate
						int i = 0;
						for (GoodType g : GoodType.values()) {
							prices.get(targetGood).put(g, bestPrices[i]);
							i++;
						}
						boughtGood(targetGood);
					}
					else {
						System.out.println("Attempt failed: requested book already sold.");
					}

					step = 4;
				}
				else {
					block();
				}
				break;
			}        
		}

		public boolean done() {
			if (step == 2 && bestSeller == null) {
				System.out.println("Attempt failed: "+targetGood+" not available for sale");
			}
			return ((step == 2 && bestSeller == null) || step == 4);
		}
	}  // End of inner class BuySomething

	private void boughtGood(GoodType good) {
		property.put(good, property.get(good) + 1); // this is bought from the other
		for (GoodType g : GoodType.values()) {
			property.put(g, property.get(g) - prices.get(good).get(g)); // these goods goes to the other
			if (property.get(g) < 0) {
				property.put(g, 0); // shouldn't happen, but who knows
			}
		}
		logProperty();
		produceGood();
	}

	private void soldGood(GoodType good) {
		for (GoodType g : GoodType.values()) {
			property.put(g, property.get(g) + prices.get(good).get(g)); // these goods come from the other
		}
		property.put(good, property.get(good) - 1); // this is sold to the buyer
		if (property.get(good) < 0) {
			property.put(good, 0); // shouldn't happen, but who knows
		}
		logProperty();
		produceGood();
	}

	private void produceGood() {
		boolean keepProducing = true;
		while (keepProducing) {
			// check if we can produce
			int i = GoodType.values().length;
			for (GoodType g : GoodType.values()) {
				if (property.get(g) < coefficients[i]) {
					keepProducing = false;
					break; // I miss at least a unit of g
				}
				i++;
			}
			// we can. so look at productions
			String msg = getAID().getLocalName()+" produces ";
			for (i = 0; i < GoodType.values().length; ++i) {
				if (coefficients[i] > 0) {
					GoodType g = GoodType.values()[i];
					property.put(g, property.get(g) + coefficients[i]); // these are produced
					msg += "( "+coefficients[i]+" of "+g+") ";
				}
			}
			msg += "with ";
			for (; i < GoodType.values().length*2; ++i) {
				if (coefficients[i] > 0) {
					GoodType g = GoodType.values()[i - GoodType.values().length];
					property.put(g, property.get(g) - coefficients[i]); // these are spent in the production
					msg += "( "+coefficients[i]+" of "+g+") ";
				}
			}
			System.out.println(msg);
			logProperty();
		}
	}
	
	private void logProperty() {
		String msg = "";
		for (GoodType g : GoodType.values()) {
			msg += "\t" + property.get(g);
		}
		data_file.println(msg);
		// update gui
		gui.updateNumbers();
	}

	private class EcononomicAgentGui extends JFrame {	
		private Map<GoodType, JTextField> dataFields;
	
		EcononomicAgentGui() {
			super(EconomicAgent.this.getLocalName());
	
			dataFields = new HashMap<GoodType, JTextField>();
		
			JPanel p = new JPanel();
			p.setLayout(new GridLayout(2, GoodType.values().length));
			for (GoodType g : GoodType.values()) {
				p.add(new JLabel(g.name()));
			}
			for (GoodType g : GoodType.values()) {
				JTextField field = new JTextField();
				field.setText(Integer.toString(property.get(g)));
				field.setEditable(false);
				p.add(field);
				dataFields.put(g, field);
			}
			getContentPane().add(p, BorderLayout.CENTER);
		
			// Make the agent terminate when the user closes 
			// the GUI using the button on the upper right corner	
			addWindowListener(new WindowAdapter() {
				public void windowClosing(WindowEvent e) {
					EconomicAgent.this.doDelete();
				}
			} );
			setResizable(false);
		}
	
		public void showGui() {
			pack();
			super.setVisible(true);
		}

		public void updateNumbers() {
			for (GoodType g : GoodType.values()) {
				dataFields.get(g).setText(Integer.toString(property.get(g)));
			}
			showGui();
		}
	};

}