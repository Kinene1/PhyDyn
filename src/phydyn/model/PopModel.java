package phydyn.model;


import beast.core.CalculationNode;

public abstract class PopModel extends CalculationNode {
	
	public int numDemes, numNonDemes; // m = numDemes
	public String[] demeNames, nonDemeNames;
	boolean diagF;

	@Override
	public void initAndValidate() {
		// TODO Auto-generated method stub

	}
	
	/* Legacy needed by Density class -  defaultt implementation */
	public int getNumStates() { return numDemes; }
	
	public abstract boolean update();
	public abstract TimeSeriesFGY getTimeSeries();
	
	public abstract boolean hasEndTime();
	public abstract double getEndTime();
	public abstract void setEndTime(double newt1);
	public abstract void setStartTime(double t0);
	public abstract double getStartTime();
	
	
	public void setDiagF(boolean b) { diagF = b; }
	public boolean isDiagF() { return diagF; }
	public boolean isConstant() { return false; }
	
	// Replace this with call to hashmap
	public int indexOf(String[] a, String s) {
		for(int i=0; i < a.length; i++)
			if (a[i].equals(s))
				return i;
		return -1;
	}
	
	/* Maps stateNames to stateNumbers 0,...,m-1 */
	public int getStateFromName(String name) {
		return this.indexOf(demeNames,name);
	}
	
	/* Mostly (all?) used for MLE */
	public abstract int getParamIndex(String paramName);
	public abstract int getYindex(String yName);
	public abstract void updateRate(String rateName, double v);
	public abstract boolean updateParam(String paramName, double paramValue);

}