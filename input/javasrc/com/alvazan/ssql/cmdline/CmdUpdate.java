package com.alvazan.ssql.cmdline;

import java.util.HashMap;
import java.util.Map;

import com.alvazan.orm.api.base.NoSqlEntityManager;
import com.alvazan.orm.api.exc.ParseException;
import com.alvazan.orm.api.z3api.NoSqlTypedSession;

public class CmdUpdate {

	void processUpdate(String cmd, NoSqlEntityManager mgr) {
		NoSqlTypedSession s = mgr.getTypedSession();
		try {
			int count = s.updateQuery(cmd);
			mgr.flush();
			println(count + " row updated");
			
		} catch(ParseException e) {
			Throwable childExc = e.getCause();
			throw new InvalidCommand("Scalable-SQL command was invalid.  Reason="+childExc.getMessage()+" AND you may want to add -v option to playcli to get more info", e);
		}
	}

	private Map<String,Object> parseQueryforUpdate(String command) {
		Map<String, Object> updateMap = new HashMap<String, Object>();
		int index = command.toLowerCase().indexOf("set");
		if(index < 0) {
			throw new InvalidCommand("Command requires a SET");
		}
		/*String cf = withoutSlash.substring(0, index);
		String lastPart = withoutSlash.substring(index+1);
		return goMore(mgr, cf, lastPart);*/
		return updateMap;
	}

	private void println(String msg) {
		System.out.println(msg);
	}
}
