////////////////////////////////////////////////////////////////////////////////
//
//
//
////////////////////////////////////////////////////////////////////////////////

package jing.rxn;

import java.util.ListIterator;
import java.util.StringTokenizer;
import jing.chem.Species;
import jing.param.Pressure;
import jing.param.Temperature;
import jing.rxnSys.CoreEdgeReactionModel;
import jing.rxnSys.NegativeConcentrationException;
import jing.rxnSys.SystemSnapshot;

/**
 * Represents either a pressure-dependent path reaction (a reaction connecting
 * two isomers directly) or a pressure-dependent net reaction (a reaction not
 * necessarily connecting two isomers directly). The former is provided to
 * a pressure-dependent kinetics estimator in order to generate the latter.
 * The latter are then treated as core/edge reactions based on the status
 * of the isomers they connect.
 * 
 * There are three types of path reactions: isomerizations
 * (A1 --> A2), associations (B + C --> A), and dissociations (A --> B + C).
 *
 * @author jwallen
 */
public class PDepReaction extends Reaction {

	/**
	 * An enumeraction of pressure-dependent reaction types:
	 * <ul>
	 * <li>NONE - The reaction type could not be assessed.
	 * <li>ISOMERIZATION - The reaction has the form A --> B.
	 * <li>ASSOCIATION - The reaction has the form B + C [+ ...] --> A.
	 * <li>DISSOCIATION - The reaction has the form A --> B + C [+ ...].
	 * <li>OTHER - The reaction has the form A + B [+ ...] --> P + Q [+ ...].
	 * </ul>
	 */
	public enum Type { NONE, ISOMERIZATION, ASSOCIATION, DISSOCIATION, OTHER };
	
	/**
	 * A reference to the reactant well.
	 */
	private PDepIsomer reactant;
	
	/**
	 * A reference to the product well.
	 */
	private PDepIsomer product;
	
	/**
	 * The pressure-dependent kinetics, as fitted to a set of Chebyshev
	 * polynomials.
	 */
	private ChebyshevPolynomials chebyshev;
	
	/**
	 * The reverse PDepReaction. Is used instead of Reaction.reverse when
	 * dealing with PDepReaction objects.
	 */
	private PDepReaction pDepReverse;
			
	//==========================================================================
	//
	//	Constructors
	//
	
	/**
	 * Create a pressure-dependent path reaction connecting isomers reac and
	 * prod and having high-pressure Arrhenius kinetics as found in rxn.
	 * @param reac The reactant PDepIsomer
	 * @param prod The product PDepIsomer
	 * @param rxn A Reaction containing the appropriate high-pressure kinetics
	 */
	public PDepReaction(PDepIsomer reac, PDepIsomer prod, Reaction rxn) {
		super();
		structure = rxn.structure;
		kinetics = rxn.kinetics;
		reverseReaction = rxn.reverseReaction;
		if (structure == null)
			structure = new Structure(reac.getSpeciesList(), prod.getSpeciesList(), 1);
		setReactant(reac);
		setProduct(prod);
		chebyshev = null;
	}
	
	/**
	 * Create a pressure-dependent path reaction connecting isomers reac and
	 * prod and having high-pressure Arrhenius kinetics as found in rxn.
	 * @param reac The reactant PDepIsomer
	 * @param prod The product PDepIsomer
	 * @param kin The high-pressure kinetics for the forward reaction
	 */
	public PDepReaction(PDepIsomer reac, PDepIsomer prod, Kinetics kin) {
		super();
		structure = null;
		kinetics = kin;
		reverseReaction = null;
		if (structure == null)
			structure = new Structure(reac.getSpeciesList(), prod.getSpeciesList(), 1);
		setReactant(reac);
		setProduct(prod);
		chebyshev = null;
	}
	
	/**
	 * Create a pressure-dependent net reaction connecting isomers reac and
	 * prod and having k(T, P) kinetics as approximated by the Chebyshev 
	 * polynomials of cheb.
	 * @param reac The reactant PDepIsomer
	 * @param prod The product PDepIsomer
	 * @param cheb A set of Chebyshev polynomials representing the fitted k(T, P)
	 */
	public PDepReaction(PDepIsomer reac, PDepIsomer prod, ChebyshevPolynomials cheb) {
		super();
		structure = new Structure(reac.getSpeciesList(), prod.getSpeciesList(), 1);
		setReactant(reac);
		setProduct(prod);
		setHighPKinetics(null);
		chebyshev = cheb;
	}
	
	//==========================================================================
	//
	//	Accessors
	//
	
	/**
	 * Returns the reaction type as one of the options given in the Type
	 * enumeration.
	 * @return The reaction type
	 */
	public Type getType() {
		if (reactant == null || product == null)
			return Type.NONE;
		else if (reactant.isUnimolecular() && product.isUnimolecular())
			return Type.ISOMERIZATION;
		else if (reactant.isMultimolecular() && product.isUnimolecular())
			return Type.ASSOCIATION;
		else if (reactant.isUnimolecular() && product.isMultimolecular())
			return Type.DISSOCIATION;
		else if (reactant.isMultimolecular() && product.isMultimolecular())
			return Type.OTHER;
		else
			return Type.NONE;
	}
	
	/**
	 * Returns the reactant isomer
	 * @return The reactant isomer
	 */
	public PDepIsomer getReactant() {
		return reactant;
	}
	
	/**
	 * Returns the product isomer
	 * @return The reactant isomer
	 */
	public PDepIsomer getProduct() {
		return product;
	}
	
	/**
	 * Sets the reactant isomer to r.
	 * @param r The new reactant isomer
	 */
	public void setReactant(PDepIsomer r) {
		reactant = r;
	}
	
	/**
	 * Sets the product isomer to r.
	 * @param r The new product isomer
	 */
	public void setProduct(PDepIsomer p) {
		product = p;
	}
	
	/**
	 * An alias for Reaction.getKinetics() that emphasizes that the returned
	 * kinetics are only valid in the high-pressure limit.
	 * @return The high-pressure Arrhenius kinetics for the reaction
	 */
	public Kinetics getHighPKinetics() {
		return getKinetics();
	}
	
	/**
	 * An alias for Reaction.setKinetics() that emphasizes that the 
	 * kinetics are only valid in the high-pressure limit.
	 * @param kin The new high-pressure Arrhenius kinetics for the reaction
	 */
	public void setHighPKinetics(Kinetics kin) {
		setKinetics(kin);
	}
	
	/** 
	 * Returns the Chebyshev polynomial fit to k(T, P) for this net reaction.
	 * @return The Chebyshev polynomial fit to k(T, P) for this net reaction
	 */
	public ChebyshevPolynomials getChebyshevFit() {
		return chebyshev;
	}
	
	/** 
	 * Sets the Chebyshev polynomial fit to k(T, P) for this net reaction.
	 * @param cheb The new Chebyshev polynomial fit to k(T, P) for this net reaction
	 */
	public void setChebyshevFit(ChebyshevPolynomials cheb) {
		chebyshev = cheb;
	}
	
	/**
	 * Gets the current reverse reaction, using PDepReaction.pDepReverse rather
	 * than Reaction.reverse.
	 * @return The current pressure-dependent reverse reaction
	 */
	@Override
	public Reaction getReverseReaction() {
		return pDepReverse;
	}
	
	/**
	 * If the provided reaction is pressure-dependent, sets the reverse reaction
	 * to that reaction.
	 * @param rxn The new pressure-dependent reverse reaction
	 */
	@Override
	public void setReverseReaction(Reaction rxn) {
		if (rxn instanceof PDepReaction)
			pDepReverse = (PDepReaction) rxn;
	}
	
	//==========================================================================
	//
	//	Other methods
	//
	
	/**
	 * Returns true if this reaction represents an edge reaction (that is,
	 * all species in the reactant isomer are in the model core and all species 
	 * in the product isomer are in the current model edge), and false
	 * otherwise.
	 * @param cerm The current core/edge reaction model
	 * @return True if an edge reaction, false if not
	 */
	public boolean isEdgeReaction(CoreEdgeReactionModel cerm) {
		// All reactant species must be in the core
		for (int i = 0; i < reactant.getNumSpecies(); i++) 
			if (cerm.containsAsReactedSpecies(reactant.getSpecies(i)) == false)
				return false;
		// At least one product species must be in the edge
		for (int i = 0; i < product.getNumSpecies(); i++) 
			if (cerm.containsAsUnreactedSpecies(product.getSpecies(i)) == true)
				return true;
		// If here, then reaction is not on the edge
		return false;
	}
	
	/**
	 * Returns true if this reaction represents a core reaction (that is,
	 * all species in the reactant and product isomers are in the current
	 * model core), and false otherwise.
	 * @param cerm The current core/edge reaction model
	 * @return True if a core reaction, false if not
	 */
	public boolean isCoreReaction(CoreEdgeReactionModel cerm) {
		// All reactant species must be in the core
		for (int i = 0; i < reactant.getNumSpecies(); i++) 
			if (cerm.containsAsReactedSpecies(reactant.getSpecies(i)) == false)
				return false;
		// All product species must be in the edge
		for (int i = 0; i < product.getNumSpecies(); i++) 
			if (cerm.containsAsReactedSpecies(product.getSpecies(i)) == false)
				return false;
		// If here, then reaction is in the core
		return true;
	}
	
	/**
	 * Returns true if the reaction is a net reaction, signified by it having
	 * a non-null Chebyshev polynomial fit for k(T, P).
	 * @return True if the reaction is a net reaction, false otherwise
	 */
	public boolean isNetReaction() {
		return (chebyshev != null);
	}
	
	/**
	 * Returns the reaction as an ASCII string.
	 * @return A string representing the reaction equation in ASCII test.
	 */
	@Override
	public String toString() {
		if (reactant == null || product == null)
			return "";
		else
			return (reactant.toString() + " --> " + product.toString());
	}
	
	/**
	 * Calculates the rate coefficient for the forward reaction at the 
	 * specified temperature and pressure. Uses the Chebyshev polynomial fit
	 * if present, then reverts to the high-pressure Arrhenius fit if present.
	 * @param temperature The temperature to determine the rate coefficient at
	 * @param pressure The pressure to determine the rate coefficient at
	 * @return The calculated rate coefficient for the forward reaction
	 */
	public double calculateRate(Temperature temperature, Pressure pressure) {
		if (chebyshev != null)
			return chebyshev.calculateRate(temperature, pressure);
		else if (kinetics != null)
			return kinetics.calculateRate(temperature);
		else
			return 0.0;
			
	}
	
	/**
	 * Calculates the flux of this reaction given the provided system snapshot.
	 * The system snapshot contains the temperature, pressure, and 
	 * concentrations of each core species.
	 * @param ss The system snapshot at which to determine the reaction flux
	 * @return The determined reaction flux
	 */
	public double calculateFlux(SystemSnapshot ss) {
		return calculateForwardFlux(ss) - calculateReverseFlux(ss);
	}

	/**
	 * Calculates the forward flux of this reaction given the provided system snapshot.
	 * The system snapshot contains the temperature, pressure, and
	 * concentrations of each core species.
	 * @param ss The system snapshot at which to determine the reaction flux
	 * @return The determined reaction flux
	 */
	public double calculateForwardFlux(SystemSnapshot ss) {
		Temperature T = ss.getTemperature();
		Pressure P = ss.getPressure();
		double forwardFlux = calculateRate(T, P);
		for (ListIterator<Species> iter = reactant.getSpeciesListIterator(); iter.hasNext(); ) {
			Species spe = iter.next();
			double conc = 0.0;
			if (ss.getSpeciesStatus(spe) != null)
				conc = ss.getSpeciesStatus(spe).getConcentration();
			if (conc < 0)
	        	throw new NegativeConcentrationException(spe.getName() + ": " + String.valueOf(conc));
			forwardFlux *= conc;
		}
		return forwardFlux;
	}

	/**
	 * Calculates the flux of this reaction given the provided system snapshot.
	 * The system snapshot contains the temperature, pressure, and
	 * concentrations of each core species.
	 * @param ss The system snapshot at which to determine the reaction flux
	 * @return The determined reaction flux
	 */
	public double calculateReverseFlux(SystemSnapshot ss) {
		return pDepReverse.calculateForwardFlux(ss);
	}

	/**
	 * Returns true if either the forward or reverse reaction matches the
	 * provided reaction.
	 * @param rxn The reaction to compare the current reaction to
	 * @return True if the reactions are the same, false if not
	 */
	public boolean equals(PDepReaction rxn) {
		if (rxn.reactant.equals(reactant) && rxn.product.equals(product))
			return true;
		else if (rxn.reactant.equals(product) && rxn.product.equals(reactant))
			return true;
		else
			return false;
	}

	/**
	 * Returns true if either the forward or reverse reaction matches the
	 * provided reaction.
	 * @param rxn The reaction to compare the current reaction to
	 * @return True if the reactions are the same, false if not
	 */
	public boolean equals(Reaction rxn) {
		return super.equals(rxn);
	}
	
	/**
	 * Generates the reverse PDepReaction, overriding Reaction.generateReverseReaction().
	 */
	@Override
	public void generateReverseReaction() {
        if (chebyshev != null) {
			PDepReaction r = new PDepReaction(product, reactant, chebyshev);
			setReverseReaction(r);
			r.setReverseReaction(this);
		}
		else {
			super.generateReverseReaction();
			PDepReaction r = new PDepReaction(product, reactant, super.getReverseReaction()); 
			setReverseReaction(r); 
			r.setReverseReaction(this);
		}
    }
	
	@Override
	public boolean hasReverseReaction() {
		return (pDepReverse != null);
	}
	
	/**
	 * A holdover from the old PDepNetReaction class, used by
	 * PDepReaction.toChemkinString().
	 * @param p_string A Chemkin string from a reaction structure
	 * @return The parsed version of the string
	 */
	public String formPDepSign(String p_string) {
        StringTokenizer st = new StringTokenizer(p_string, "=");
        String s1 = st.nextToken();
        s1 += "(+m)=";
        String s2 = st.nextToken();
        s2 += "(+m)";
        return (s1+s2);
    }
	
	/**
	 * A holdover from the old PDepNetReaction class, used to generate a 
	 * Chemkin string. For a path reaction Reaction.toChemkinString() is used,
	 * while for a net reaction a different string is constructed.
	 * @param t A temperature at which to evaluate the Chemkin string at
	 * @return The resulting Chemkin string
	 */
	@Override
	public String toChemkinString(Temperature t) {
        if (chebyshev != null) {
			String result = formPDepSign(getStructure().toChemkinString(true).toString()) + '\t' + "1.0E0 0.0 0.0" + '\n';
			result += chebyshev.toChemkinString() + '\n';
			return result;
		}
		else if (kinetics != null)
			return super.toChemkinString(t);
		else
			return "";
    
    }
}