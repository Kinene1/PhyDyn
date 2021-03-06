package phydyn.distribution;

import java.util.List;
import java.lang.Math;

import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;

import beast.core.Citation;
import beast.core.Description;
import beast.core.Input;

import beast.evolution.tree.Node;
import beast.evolution.tree.TraitSet;

import beast.evolution.tree.coalescent.IntervalType;

import phydyn.model.TimeSeriesFGY;
import phydyn.model.TimeSeriesFGY.FGY;


/**
 * @author Igor Siveroni
 * based on original code by David Rasmussen, Nicola Muller
 */

@Description("Calculates the probability of a beast.tree  under a structured population model "
		+ "using the framework of Volz (2012).")
@Citation("Erik M. Volz. 2012. Complex population dynamics and the coalescent under neutrality."
		+ "Genetics. 2013 Nov;195(3):1199. ")
public abstract class STreeLikelihood extends STreeGenericLikelihood  {
	
	
	// replace above with model - already inherited
	public Input<Boolean> fsCorrectionsInput = new Input<>(
			"finiteSizeCorrections", "Finite Size Corrections", new Boolean(false));
	
	public Input<Boolean> approxLambdaInput = new Input<>(
			"approxLambda", "Use approximate calculation of Lambda (sum)", new Boolean(false));
	
	public Input<Double> forgiveAgtYInput = new Input<>(
			"forgiveAgtY", "Threshold for tolerating A (extant lineages) > Y (number demes in simulation): 1 (always), 0 (never)", 
			new Double(1.0));
	
	public Input<Double> penaltyAgtYInput = new Input<>(
			"penaltyAgtY", "Penalty applied to likelihod after evaluating A>Y at the end of each interval",
			new Double(1.0));
	
	public Input<Boolean> forgiveYInput = new Input<>("forgiveY",
			"Tolerates Y < 1.0 and sets Y = max(Y,1.0)",  new Boolean(false));
	
	/* inherits from STreeGenericLikelihood:
	 *  public PopModelODE popModel;
	 	public TreeInterface tree;
	 	public STreeIntervals intervals;
	 	public int numStates; 	 
	 	protected int[] nodeNrToState; 
	 */
		   
    public TimeSeriesFGY ts;
	public int samples;
	public int nrSamples;
	
	private SolverIntervalForward aceSolver=null;
	
	protected double[] FdivY2; // island = 1/2Ne
	
	// loop variables
	protected int tsPoint;
	double h, t, tsTimes0;
      
    // debug
    protected int numCoal=0;
    
   
    
    @Override
    public void initAndValidate() {
    	super.initAndValidate(); /* important: call first */
    	stateProbabilities = new StateProbabilitiesArray(); // declared in superclass    
    	//initValues();
    	if (ancestralInput.get()) {
    		if (popModel.isConstant())
    			aceSolver = new SolverfwdConstant(this);
    		else
    			aceSolver = new Solverfwd(this);
    	}
 
    }
    
    public boolean initValues()   {
    	
    	super.initValues();
    	// if t1 has t1 Input, do nothing, else t1 to Tree's height
    	if (!popModel.hasEndTime()) {
    		if (tree.getDateTrait()==null) {
    			//System.out.println(" NO date trait, setting t1 = "+intervals.getTotalDuration());
    			popModel.setEndTime( intervals.getTotalDuration() );
    		} else {
    			if (tree.getDateTrait().getTraitName().equals( TraitSet.DATE_BACKWARD_TRAIT)) {
    				//System.out.println(" backward date trait, setting t1 = "+intervals.getTotalDuration());
    				popModel.setEndTime( intervals.getTotalDuration());
    			} else {  
    				//System.out.println(" Date trait, setting t1 = "+ tree.getDateTrait().getDate(0) );
    				popModel.setEndTime( tree.getDateTrait().getDate(0));
    			}
    		}
    	} else {
    		//System.out.println(" Using t1 = "+popModel.getEndTime());
    	}
    	
       	double trajDuration = popModel.getEndTime() - popModel.getStartTime();
    	//System.out.println("T root = "+(popModel.getEndTime()-intervals.getTotalDuration() ));
    	//System.out.println("Tree height="+intervals.getTotalDuration());
    	   	
    	if (trajDuration < intervals.getTotalDuration()) {
    		// if island model / constant population: extend time frame
    		if (popModel.isConstant()) {
    			System.out.println("Updating t0 to fit tree height (constant population)");
    			System.out.println("new t0="+(popModel.getEndTime()- intervals.getTotalDuration()));
    			popModel.setStartTime(popModel.getEndTime()- intervals.getTotalDuration());
    		} else {
    			if (forgiveT0Input.get()) {
    				System.out.println("t0 too low - using constant population coalescent for missing population info");   		
    			} else {
    				System.out.println("t0 too low - logP = -Inf");
    				return true;
    			}
    		}
    	}
        
    	boolean reject = popModel.update(); // compute timeseries
    	if (reject) {
        	System.out.println("rejecting population update");
            return true;
        }
    	
    	ts = popModel.getTimeSeries();
    	
		FGY fgy = ts.getFGY(0);
		DoubleMatrix Y = fgy.Y; // ts.getYs()[t];
		DoubleMatrix F = fgy.F; // ts.getFs()[t];
		
		if (popModel.isConstant() && popModel.isDiagF()) {
			FdivY2 = new double[numStates];
			for(int i=0; i < numStates; i++) {
				FdivY2[i] = F.get(i,i) / Y.get(i) / Y.get(i);
			}
		}
    	
        nrSamples = intervals.getSampleCount();
              
        nrSamples++;
        // Extant Lineages and state probabilities
        stateProbabilities.init(tree.getNodeCount(), numStates);
        
        if (aceSolver!=null)
        	aceSolver.initValues(this);    	
        return false;
    }
     
    public double calculateLogP() {
    	//System.out.println("Calculate logLh");
    	//printMemory();  	
    	boolean reject = initValues();
    	
        if (reject) {
            logP = Double.NEGATIVE_INFINITY;
            return logP;
        }
        
        double trajDuration = popModel.getEndTime() - popModel.getStartTime();
        logP = 0;  
        
        final int intervalCount = intervals.getIntervalCount();
        
        double pairCoalRate;
  
        IntervalType intervalType;
       
        // initialisations        		
        int interval = 0, numExtant, numLeaves;  	// first interval
        tsPoint = 0;
        h = 0.0;		// used to initialise first (h0,h1) interval 
        t = tsTimes0 = ts.getTime(0); // tsTimes[0];
        
        double   lhinterval;
        numLeaves = tree.getLeafNodeCount();
        double duration;
              
        do { 
        	duration = intervals.getInterval(interval);
        	      	
        	if (trajDuration < (h+duration)) break;
        	lhinterval = processInterval(interval, duration, ts);
        	    	
        	if (lhinterval == Double.NEGATIVE_INFINITY) {
    			logP = Double.NEGATIVE_INFINITY;
				return logP;
    		} 	
        		
        	// Assess Penalty
        	numExtant = stateProbabilities.getNumExtant();
        	//double YmA = ts.getYs()[tsPoint].sum() - numExtant;
        	double YmA = ts.getFGY(tsPoint).Y.sum() - numExtant;
        	if (YmA < 0) {
        		//System.out.println("Y-A < 0");
        		if ((numExtant/numLeaves) > forgiveAgtYInput.get()) {
        			System.out.println("Minus Infinity: A > Y");
        			lhinterval = Double.NEGATIVE_INFINITY;
        			logP = Double.NEGATIVE_INFINITY;
        			return logP;
        		} else {
        			lhinterval += lhinterval*Math.abs(YmA)*penaltyAgtYInput.get();
        		}
        	}      	
        	logP += lhinterval;
        	
        	// Make sure times and heights are in sync
        	// assert(h==hEvent) and assert(t = tsTimes[0] - h)
        	      	     	
        	intervalType = intervals.getIntervalType(interval);
        	if (intervalType == IntervalType.SAMPLE) {
       			processSampleEvent(interval);
        	} else if (intervalType == IntervalType.COALESCENT) {     			
        		pairCoalRate = processCoalEvent(tsPoint, interval);
        		if (pairCoalRate >= 0) {
        			logP += Math.log(pairCoalRate);
        		}
        	} else {
        		throw new IllegalArgumentException("Unknown Interval Type");
        	}
        	      	
        	if (Double.isNaN(logP)) {
        		System.out.println("NAN - quitting likelihood");
        		logP = Double.NEGATIVE_INFINITY;
				return logP;
        	} else if (logP == Double.NEGATIVE_INFINITY) {
				return logP;
    		} 
    		       	
        	interval++;
        } while (interval < intervalCount);
        
        int lastInterval = interval;
                   
        if (interval < intervalCount) { // root < t0
        	// process first half of interval
        	duration = trajDuration - h;
        	lhinterval = processInterval(interval, duration, ts);
        	logP += lhinterval;
        	// at this point h = trajDuration
        	// process second half of interval, and remaining intervals
        	duration = intervals.getInterval(interval)-duration;
        	
        	logP += calculateLogP_root2t0(interval, duration);
        	       	
        }
        
        if (ancestralInput.get()) {
        	computeAncestralStates(lastInterval);
        }
               
        ts = null;
        if (Double.isInfinite(logP)) logP = Double.NEGATIVE_INFINITY;
                
        return logP;
   	
    }
    
    /* Computes the likelihood of the remaining of the tree: from t0 to root
     * Default: Uses coalescent with constant population size
     * current position: t,h,tsPoint */   
    public double calculateLogP_root2t0(int interval, double duration) {
    	double comb, coef, lambda, Ne;
    	double lh=0;
    	// At this point h = trajDuration
    	// process second half of interval
    	double numLineages = intervals.getIntervalCount();
    	comb = numLineages*(numLineages-1)/2.0;
    	if (NeInput.get()==null) {
    		lambda = computeLambdaSum(tsPoint);
    		Ne = comb/lambda;  // should be user input - first trying this
    	} else {
    		Ne = NeInput.get().getValue();
    	}   	
    	coef = comb/Ne;
    	lh += (Math.log(1/Ne) -  coef*duration);   	
    	interval++;
    	// process remaining intervals
    	final int intervalCount = intervals.getIntervalCount();
    	while (interval < intervalCount) {
    		duration = intervals.getInterval(interval);       		
    		numLineages = intervals.getIntervalCount();
    		coef = numLineages*(numLineages-1)/Ne;
        	//logP += coef*duration + Math.log(coef);
        	//logP += Math.log(coef) - coef*duration;
        	lh += (Math.log(1/Ne) - coef*duration);
    		interval++;
    	}
    	return lh;
    }       

    protected void computeAncestralStates(int interval) {
    		/* traversal state: interval, h, t, tsPoint */
    		final int intervalCount = intervals.getIntervalCount();
    		if (interval < intervalCount) {
    			System.out.println("Warning: calculating ancestral states with root states unknown");
    		} else {
    			interval = intervalCount; // should be this value already
    		}

    		DoubleMatrix[] backwardProbs = stateProbabilities.clearAncestralProbs();
    		FGY fgy;
    		DoubleMatrix pParent, pChild;
    		
    		// SolverQfwd solverQfwd = new SolverQfwd(intervals,ts, numStates);
    		DoubleMatrix Q;
    		double t0, t1, duration;
    	
    		interval--;
    		int lineageAdded = intervals.getLineagesAdded(interval).get(0).getNr();  // root 
    		pParent = stateProbabilities.removeLineageNr(lineageAdded);
    		stateProbabilities.storeAncestralProbs(lineageAdded, pParent, false);
    		t1 = tsTimes0-intervals.getIntervalTime(interval);
    		
    		while (interval > 0) {
    			duration = intervals.getInterval(interval);
       			t0 = t1;
    			t1 = tsTimes0-intervals.getIntervalTime(interval-1);
    			
    			// initiliaze new lineages (children)
    			if (intervals.getIntervalType(interval)==IntervalType.COALESCENT) {
    				List<Node> coalLineages = intervals.getLineagesRemoved(interval);
    				if (coalLineages.size()>0) {
    					fgy = ts.getFGY(tsPoint);
    					pChild = pParent.mmul(fgy.F);
    					pChild.divi(pChild.sum());
    					pChild.addi(pParent);
    					pChild.divi(2);
    					stateProbabilities.addLineage(coalLineages.get(0).getNr(), pChild);
    					stateProbabilities.addLineage(coalLineages.get(1).getNr(), pChild.add(0)); // clone  		
    				}
    			}
        	
    			// update tsPoint
    			tsPoint = ts.getTimePoint(t0, tsPoint);
        	
    			// compute QQ and update extant lineages (forward)
    			if (duration>0) {
    				// solve and update extant probabilities
    				aceSolver.solve(t0, t1, tsPoint, this);   				
    			}
    			// remove incoming lineage
    			interval--;
    			lineageAdded = intervals.getLineagesAdded(interval).get(0).getNr();  
    			pParent = stateProbabilities.removeLineageNr(lineageAdded); 
    			// update incoming lineage - pParent pointing to original vector
    			pParent.muli(backwardProbs[lineageAdded]);
    			pParent.divi(pParent.sum());
    			stateProbabilities.storeAncestralProbs(lineageAdded, pParent, false);   		
    		}
    	// debug
    	// this.stateProbabilities.printAncestralProbabilities();
    }
    
    /* updates t,h,tsPoint and lineage probabilities */
    /* default version: logLh=0, state probabilities remain unchanged */
    protected double processInterval(int interval, double intervalDuration, TimeSeriesFGY  ts) {
        double segmentDuration;
        
    	double hEvent = h + intervalDuration; 		// event height
    	double tEvent = ts.getTime(0) - hEvent;      // event time
    	
    	// traverse timeseries until closest latest point is found
    	// tsTimes[tsPoint+1] <= tEvent < tsTimes[tsPoint] -- note that ts points are in reverse time
    	// t = tsTimes[0] - h;
    	
    	// Process Interval
    	double lhinterval = 0;
    	while (ts.getTime(tsPoint+1) > tEvent) {
    		segmentDuration = t - ts.getTime(tsPoint+1);
    		// lhinterval += processIntervalSegment(tsPoint,segmentDuration);
    		if (lhinterval == Double.NEGATIVE_INFINITY) {
    			return Double.NEGATIVE_INFINITY;
    		} 				
    		t = ts.getTime(tsPoint+1);
    		h += segmentDuration;
    		tsPoint++;
    		// tsTimes[0] = t + h -- CONSTANT
    	}
    	// process (sub)interval before event
    	segmentDuration = hEvent - h;  // t - tEvent
    	if (segmentDuration > 0) {
    		// lhinterval += processIntervalSegment(tsPoint,segmentDuration);
    		
    		if (lhinterval == Double.NEGATIVE_INFINITY) {
    			return Double.NEGATIVE_INFINITY;
    		} 	
    	}
    	// update h and t to match tree node/event
    	h = hEvent;
    	t = ts.getTime(0) - h;
    	return lhinterval;
    }
    
    
    /* currTreeInterval must be a SAMPLE interval i.e. the incoming lineage must be a Leaf/Tip */
    protected void processSampleEvent(int interval) {
    	int sampleState;
		List<Node> incomingLines = intervals.getLineagesAdded(interval);
		for (Node l : incomingLines) {	
			// System.out.println("Nr="+l.getNr()+" ID="+l.getID());
			/* uses pre-computed nodeNrToState */
			sampleState = nodeNrToState[l.getNr()];	/* suceeds if node is a leaf, otherwise state=-1 */	
			DoubleMatrix sVec = DoubleMatrix.zeros(1,numStates); // row-vector
			sVec.put(sampleState, 1.0);
			stateProbabilities.addLineage(l.getNr(),sVec);
			if (computeAncestral)
				stateProbabilities.storeAncestralProbs(l.getNr(), sVec, true);
		}	
    }
    
    
          
    protected double processCoalEvent(int t, int currTreeInterval) {
    	List<DoubleMatrix> coalVectors = stateProbabilities.getCoalescentVectors(intervals, currTreeInterval);
    	DoubleMatrix pvec1, pvec2;	
    	pvec1 = coalVectors.get(0);
    	pvec2 = coalVectors.get(1);
    	  	
		List<Node> parentLines = intervals.getLineagesAdded(currTreeInterval);
		if (parentLines.size() > 1) throw new RuntimeException("Unsupported coalescent at non-binary node");			
		//Add parent to activeLineage and initialise parent's state probability vector
		Node parentNode = parentLines.get(0);
		

		//Compute parent lineage state probabilities in p				
		FGY fgy = ts.getFGY(t);
		DoubleMatrix Y = fgy.Y; // ts.getYs()[t];
		DoubleMatrix F = fgy.F; // ts.getFs()[t];		
    	
    	if (forgiveYInput.get()) {
 			Y.maxi(1.0);
 		} else { // however, Y_i > 1e-12 by default
 			Y.maxi(1e-12); 
 		}
 
    	 double lambda=0;
	    /* Compute Lambda = pair coalescense rate */
    	DoubleMatrix pa;
    	if (popModel.isDiagF()) {
    		double[] pa_data = new double[numStates];
    		if (popModel.isConstant()) {
    			for(int j=0; j < numStates; j++ ) {
    				pa_data[j] = 2 *  pvec1.get(j) * pvec2.get(j) * FdivY2[j];
    			}
    		} else {
    			for(int j=0; j < numStates; j++ ) {
    				pa_data[j] = 2 *  pvec1.get(j) * pvec2.get(j) * F.get(j,j) /Y.get(j) / Y.get(j); 
    			}
    		}
    	    pa = new DoubleMatrix(1,numStates,pa_data);
    	} else {  		
    	    DoubleMatrix pi_Y = pvec1.div(Y);
    	    pi_Y.reshape(numStates, 1);	    	
    	    DoubleMatrix pj_Y = pvec2.div(Y);
    	    pj_Y.reshape(numStates, 1);	
    	    pa = pi_Y.mul(F.mmul(pj_Y));	    	
    	    pa.addi(pj_Y.mul(F.mmul(pi_Y)));
    	    pa.reshape(1,numStates);		
    	}
    	lambda = pa.sum(); 
	    pa.divi(lambda);
					
		stateProbabilities.addLineage(parentNode.getNr(),pa);	
		if (computeAncestral)
			stateProbabilities.storeAncestralProbs(parentNode.getNr(), pa, true);
 
		//Remove child lineages
		List<Node> coalLines = intervals.getLineagesRemoved(currTreeInterval);
		stateProbabilities.removeLineageNr(coalLines.get(0).getNr() );
		stateProbabilities.removeLineageNr(coalLines.get(1).getNr()); 
	
		if (fsCorrectionsInput.get()) {
			doFiniteSizeCorrections(parentNode,pa);
		}
		
		return lambda;
    }
        
    /*
     * tspoint: Point in time series (trajectory)
     */
    protected double computeLambdaSum(int tsPoint) {
    	DoubleMatrix A = stateProbabilities.getLineageStateSum();
		double lambdaSum = 0.0; // holds the sum of the pairwise coalescent rates over all lineage pairs
		int numExtant = stateProbabilities.getNumExtant();

		if (numExtant < 2) return lambdaSum;	// Zero probability of two lineages coalescing	
	
		FGY fgy = ts.getFGY(tsPoint);
		DoubleMatrix F = fgy.F; // ts.getFs()[tsPoint];
		DoubleMatrix Y = fgy.Y; // ts.getYs()[tsPoint];
		
		Y.maxi(1e-12);  // Fixes Y lower bound
		/*
		 * Sum over line pairs (scales better with numbers of demes and lineages)
		 */	
			
		if (approxLambdaInput.get()) {  // (A/Y)' * F * (A/Y)
			DoubleMatrix A_Y = A.div(Y);
			return A_Y.dot(F.mmul(A_Y));
		}
				
		/*
		 * Simplify if F is diagonal.
		 */
		DoubleMatrix popCoalRates,pI;
    	if(popModel.isDiagF()){
			popCoalRates = new DoubleMatrix(numStates);
			for (int k = 0; k < numStates; k++){
				final double Yk = Y.get(k); 
				if(Yk>0)
					popCoalRates.put(k, (F.get(k,k) / (Yk*Yk)));
			}
			DoubleMatrix sumStates = DoubleMatrix.zeros(numStates);
			DoubleMatrix diagElements = DoubleMatrix.zeros(numStates);
			for (int linI = 0; linI < numExtant; linI++) {
				pI = stateProbabilities.getExtantProbsFromIndex(linI);
				sumStates = sumStates.add( pI);
				diagElements = diagElements.add(pI.mul(pI));
			}
			DoubleMatrix M = sumStates.mul(sumStates);
			lambdaSum = M.sub(diagElements).mul(popCoalRates).sum();
    	} else {
    		DoubleMatrix pi_Y, pj_Y, pa, pJ;
			for (int linI = 0; linI < numExtant; linI++) {
				pI = stateProbabilities.getExtantProbsFromIndex(linI);
				pi_Y = pI.div(Y);
				pi_Y.reshape(numStates, 1);
				for (int linJ = linI+1; linJ < numExtant; linJ++) {
					pJ = stateProbabilities.getExtantProbsFromIndex(linJ);
					pj_Y = pJ.div(Y);
					pj_Y.reshape(numStates, 1);
					pa = pi_Y.mul(F.mmul(pj_Y));
					pa.addi(pj_Y.mul(F.mmul(pi_Y)));
					lambdaSum += pa.sum();
				
				}	
			}
    	}
  
    	return lambdaSum;    	
    }
    
  
    private void doFiniteSizeCorrections(Node alphaNode, DoubleMatrix pAlpha) {
    	DoubleMatrix p, AminusP;
    	int numExtant = stateProbabilities.getNumExtant();    	
    	int alphaIdx =  stateProbabilities.getLineageIndex(alphaNode.getNr());
    	
    	DoubleMatrix A = stateProbabilities.getLineageStateSum();
    	double sum;    	
    	// traverse all extant lineages
    	for(int lineIdx=0; lineIdx < numExtant; lineIdx++) {
    		if (lineIdx!=alphaIdx) {
    			p = stateProbabilities.getExtantProbsFromIndex(lineIdx);
    			AminusP = A.sub(p);
    			AminusP.maxi(1e-12);
    			// rterm = p_a / clamp(( A - p_u), 1e-12, INFINITY );
    		    DoubleMatrix rterm = pAlpha.div(AminusP);
    		    //rho = A / clamp(( A - p_u), 1e-12, INFINITY );
    		    DoubleMatrix rho = A.div(AminusP);
                //lterm = dot( rho, p_a); //
    		    double lterm = rho.dot(pAlpha);
                //p_u = p_u % clamp((lterm - rterm), 0., INFINITY) ;
    		    rterm.rsubi(lterm);
    		    //rterm.subi(lterm);
    		    //rterm.muli(-1);
    		    rterm.maxi(0.0);
    		    // p = p.muli(rterm); 
    		    sum = p.dot(rterm);
    		    if (sum > 0) {  // update p
    		    	p.muli(rterm); // in-place element-wise multiplication,
    		    	p.divi(sum);  // in-pace normalisation
    		    }
    		}   		
    	}
    	
    }
    
 
 
    
    @Override
    protected boolean requiresRecalculation() {
		 return true;
    }
    
   
    public void printMemory() {

	   	//Runtime runtime = Runtime.getRuntime();
    	//long maxMemory = runtime.maxMemory();
    	//long allocatedMemory = runtime.totalMemory();
    	//long freeMemory = runtime.freeMemory();
    	//System.out.println("free memory: " + freeMemory / 1024 );
    	//System.out.println("allocated memory: " + allocatedMemory / 1024 );
    	//System.out.println("max memory: " + maxMemory / 1024 );
    	//System.out.println("total free memory: "+(freeMemory + (maxMemory - allocatedMemory)) / 1024);
		
		 Runtime runtime = Runtime.getRuntime();
		 long totalMemory = runtime.totalMemory();
		 long freeMemory = runtime.freeMemory();
		 long maxMemory = runtime.maxMemory();
		 long usedMemory = totalMemory - freeMemory;
		 long availableMemory = maxMemory - usedMemory;
		
		System.out.println("Used Memory = "+usedMemory+ " available= "+ availableMemory);

	}
   
}
