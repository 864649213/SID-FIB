package edu.upc.fib.sid;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.ParallelBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.SearchConstraints;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetInitiator;
import jade.util.leap.Iterator;
import java.net.URISyntaxException;
import java.util.ArrayList;

import java.util.Date;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.util.Pair;

public class Industry extends Agent {
    
    private float cumulativeProfit;
    private float storageOccupied; //Cantidad ocupada del deposito de la industria. metros cubicos (m3)
    private WwtpDomain domini;
    private float industryProfitFactor;
    private float productionUpperBound; 
    private float productionLowerBound; 
    private float volFromStorage;    // from storage
    private float fractionFromStorage;
    private boolean storage_freed;
    
    private int ticks = 0;
    
    
    protected void setup() {
        
        
        try {
            domini = OntologyParser.parse();
        } catch (URISyntaxException ex) {
            Logger.getLogger(Industry.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        cumulativeProfit = 0; //Empezamos habiendo ganado 0 unidades monetarias.
        storageOccupied = 0; //Empezamos con el deposito vacio
        industryProfitFactor = 1;
        productionUpperBound = domini.getMaximumProduction();
        productionLowerBound = 0;
        
        final DFAgentDescription desc = new DFAgentDescription();
        desc.setName(getAID());

        final ServiceDescription sdesc = new ServiceDescription();
        sdesc.setName("Industry");
        sdesc.setType("Industry");
        desc.addServices(sdesc);

        try {
            DFService.register(this, getDefaultDF(), desc);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
 
        this.addBehaviour(new TickerBehaviour(this, 1000) {
            @Override
            protected void onTick() {
                // Copy these two lines in your industry agent replacing 1 with your corresponding group id
                Logger.getLogger("Industry-4").info("INDUSTRY[cumulativeProfit]: " + cumulativeProfit);
                Logger.getLogger("Industry-4").info("INDUSTRY[maximumStorage]: " + domini.getIndustryStorageAvailability());
                Logger.getLogger("Industry-4").info("INDUSTRY[storageOccupied]: " + storageOccupied);
                
                ticks++;
                float production = (productionUpperBound+productionLowerBound)/2;
                
                updateProfitIfSanction();   // Si tenemos una sanción 
                cfpWithProduction(production);
                
                if (ticks % 10 == 0) { // restablecemos los limites, cada cuantos ticks
                    productionUpperBound = domini.getMaximumProduction();
                    productionLowerBound = 0;
                }
                
            }
        });
             
    }
    
    
    public void cfpWithProduction(float production) {
        final DFAgentDescription desc = new DFAgentDescription();
        final ServiceDescription sdesc = new ServiceDescription();
        sdesc.setType("TreatmentPlant");
        desc.addServices(sdesc);
        try {
            final DFAgentDescription[] plants = DFService.search(Industry.this, getDefaultDF(), desc,
                    new SearchConstraints());
            final AID plant = plants[0].getName();
            final ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            cfp.setSender(Industry.this.getAID());
            cfp.addReceiver(plant);

            volFromStorage = 0;
            storage_freed = false;
            if (storageOccupied >= 0.2*domini.getIndustryStorageAvailability())  {
                volFromStorage = (float)0.2*domini.getIndustryStorageAvailability();
                storage_freed = true;
            }
            float wasteWater = calculateWasteVolume(production) + volFromStorage;
            fractionFromStorage = volFromStorage/wasteWater;
            cfp.setContent("(discharge :volume-water " + wasteWater + " :concentration-pollutant " + domini.getPollutantGenerated()/100 +")");
            cfp.setReplyByDate(new Date(System.currentTimeMillis() + 2000));
            
            Industry.this.addBehaviour(new ContractNetInitiator(Industry.this, cfp) {
                @Override
                protected void handleAllResponses(Vector responses, Vector acceptances) {
                    for(Object response: responses) {
                        ACLMessage msg = (ACLMessage) response;
                        if (msg.getPerformative() == ACLMessage.PROPOSE) {  // Planta propone precio
    
                            String str = msg.getContent();
                            str = str.replace("(", "").replace(")", "");
                            String[] splitted = str.split("\\s+");
                            float totalCost = Float.parseFloat(splitted[2]);
                            float costFromStorage = totalCost*fractionFromStorage;
                            float cost = totalCost - costFromStorage;
                            float profitWithoutCost = production * domini.getProfitPerTonProduced();    // ganamos solo que producimos en este tick
                            float profitFactor = profitWithoutCost/cost;
                            float realProfit = profitWithoutCost - cost;
                            
                            System.out.println("El precio sugerido por la planta de tratamiento es de: " + cost);
                            System.out.println("Mi beneficio seria de: " + realProfit);
                          
                            ACLMessage reply = msg.createReply();
                            if (profitFactor > industryProfitFactor) {  // si renta
                                System.out.println(getLocalName() + " - Accepting proposal from sender '"+ msg.getSender().getName() + "'");
                                reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                                cumulativeProfit += realProfit;
                                productionLowerBound = production;
                                if (storage_freed) storageOccupied -= volFromStorage;
                                System.out.println("En el tick " + ticks + " pasamos el agua residual a la planta " + 
                                            calculateWasteVolume(production) + "m3");
                            }
                            else {  // si no renta
                                System.out.println(getLocalName() + " - Rejecting proposal from sender '"+ msg.getSender().getName() + "'");
                                reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
                                if (Math.random() <= 0.2) { // Tiramos el agua illegalmente al rio
                                    float potentialSanctionPrice = wasteWater*domini.getPollutantGenerated()/100;
                                    if (cumulativeProfit > 2*potentialSanctionPrice) {  // Si ya tenemos bastante dinero, podemos arriesgar un poco
                                        dischargeToEnvironment(wasteWater, domini.getPollutantGenerated()/100);
                                        cumulativeProfit += profitWithoutCost;
                                        if (storage_freed) storageOccupied -= volFromStorage;
                                        System.out.println("En el tick " + ticks + " vertemos el agua residual al rio " + 
                                                wasteWater + "m3");
                                    }
                                }
                            }
                            float remainingSpace = domini.getIndustryStorageAvailability() - storageOccupied; //m3 libres en el deposito
                            float productionAvailable = remainingSpace / domini.getWastePerProduction(); // m3 / (m3/ton) = ton
                            if (productionAvailable > (domini.getMaximumProduction() - production)) productionAvailable = (domini.getMaximumProduction() - production);
                            System.out.println("------------------------ ESPACIO DISPONIBLE: " + remainingSpace + " --------- Produccion disponible: " + productionAvailable);
                            if (productionAvailable > 0) {
                                storageOccupied += calculateWasteVolume(productionAvailable);
                                cumulativeProfit += productionAvailable * domini.getProfitPerTonProduced();
                            }
                            acceptances.add(reply);
                            
                        }
                        
                        else if (msg.getPerformative() == ACLMessage.REJECT_PROPOSAL) { // Planta rechaza
                            Logger.getLogger("Industry-4").info("Propose has been rejected");
                            productionLowerBound = production;
                        }
                    }
                }
            });
        } 
        catch (FIPAException e) {
            e.printStackTrace();
        }

    }
    
    public void updateProfitIfSanction() {
        final MessageTemplate mt = new MessageTemplate((MessageTemplate.MatchExpression) aclMessage -> true);
                final ACLMessage request = Industry.this.receive(mt);
                if (request != null && request.getPerformative() == ACLMessage.REQUEST) {
                    final AID sender = request.getSender();
                    final ACLMessage reply = request.createReply();

                    try {
                        final DFAgentDescription desc = new DFAgentDescription();
                        desc.setName(sender);
                        final DFAgentDescription[] search = DFService.search(Industry.this, getDefaultDF(), desc);
                        final Iterator services = search[0].getAllServices();
                        final ServiceDescription service = (ServiceDescription) services.next();
                        // Industria 
                        if (service.getType().equals("Environment")) {
                            // recibir mensaje discharge de industria
                            String str = request.getContent();
                            System.out.println("------------------------------------------------------------------------" + str);
                            str = str.replace("(", "").replace(")", "");
                            String[] splitted = str.split("\\s+");
                            float sanctionAmount = Float.parseFloat(splitted[2]);
                            cumulativeProfit -= sanctionAmount;    
                            System.out.println("Sancion de " + sanctionAmount + " por invertir ilegalmente al río");
                        }
                    } 
                    catch (FIPAException e) {
                        e.printStackTrace();      
                    }
                } 
    }
    
    
    public float calculateWasteVolume(float productionTons) {
        float pollutantVolume = productionTons * domini.getWastePerProduction(); // t * m3/t
        return pollutantVolume;
    }
    
    public void dischargeToEnvironment(float volume, float concentration) {
        final DFAgentDescription desc = new DFAgentDescription();
        final ServiceDescription sdesc = new ServiceDescription();
        sdesc.setType("Environment");
        desc.addServices(sdesc);
        try {
            final DFAgentDescription[] agents = DFService.search(Industry.this, getDefaultDF(), desc, new SearchConstraints());
            final AID agent = agents[0].getName();
            final ACLMessage aclMessage = new ACLMessage(ACLMessage.REQUEST);
            aclMessage.setSender(Industry.this.getAID());
            aclMessage.addReceiver(agent);
            aclMessage.setContent("(discharge :volume-water " + volume +" :concentration-pollutant " + concentration +")");
            Industry.this.send(aclMessage);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    
}
