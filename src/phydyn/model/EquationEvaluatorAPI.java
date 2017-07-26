package phydyn.model;

import java.util.HashMap;
import java.util.Map;

import beast.core.Description;

@Description("Interface between PopModelODE (trajectory generator) and the equation/definitions evaluators")
public interface EquationEvaluatorAPI {
	

	
	public void updateRate(String rateName, double v);
	
	public void updateRate(int rateIndex, double v);
	
	public void updateRates(double[] rateValues);
		
	public void updateYs(double[] yValues);
	
	public void updateT0T1(double[] values);
	
	public void updateT(double value);
	
	public void executeDefinitions();
	
	public void evaluateEquations(double[] results);
	
}
