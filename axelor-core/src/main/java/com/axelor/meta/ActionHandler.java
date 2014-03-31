/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2012-2014 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.meta;

import groovy.xml.XmlUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.persistence.NoResultException;
import javax.persistence.Query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.common.StringUtils;
import com.axelor.db.JPA;
import com.axelor.db.QueryBinder;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.meta.db.MetaSelectItem;
import com.axelor.meta.schema.actions.Action;
import com.axelor.meta.schema.actions.ActionGroup;
import com.axelor.meta.schema.actions.ActionMethod;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.Context;
import com.axelor.script.GroovyScriptHelper;
import com.axelor.script.ScriptBindings;
import com.axelor.script.ScriptHelper;
import com.axelor.text.GroovyTemplates;
import com.axelor.text.Templates;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.inject.Injector;
import com.google.inject.servlet.RequestScoped;

@RequestScoped
public class ActionHandler {

	private final Logger log = LoggerFactory.getLogger(ActionHandler.class);

	private Injector injector;

	private ActionRequest request;

	private Class<?> entity;

	private Context context;

	private ScriptBindings bindings;

	private ScriptHelper scriptHelper;
	
	private Pattern pattern = Pattern.compile("^(select\\[\\]|select|action|call|eval):\\s*(.*)");
	
	private ActionHandler(Injector injector, ActionRequest request) {

		Context context = request.getContext();
		if (context == null) {
			log.debug("null context for action: {}", request.getAction());
			context = Context.create(null, request.getBeanClass());
		}

		this.injector = injector;
		this.request = request;
		this.entity = request.getBeanClass();

		this.context = context;

		this.scriptHelper = new GroovyScriptHelper(this.context);
		this.bindings = this.scriptHelper.getBindings();
	}

	@Inject
	ActionHandler(Injector injector) {
		this.injector = injector;
	}
	
	public ActionHandler forRequest(ActionRequest request) {
		return new ActionHandler(injector, request);
	}
	
	public Injector getInjector() {
		return injector;
	}

	public Context getContext() {
		return context;
	}

	public ActionRequest getRequest() {
		return request;
	}

	/**
	 * Evaluate the given <code>expression</code>.
	 *
	 * @param expression
	 * 					the expression to evaluate prefixed with action type
	 * 					followed by a <code>:</code>
	 * @param references
	 * @return
	 * 					expression result
	 */
	public Object evaluate(String expression) {

		if (Strings.isNullOrEmpty(expression)) {
			return null;
		}

		String kind = null;
		String expr = expression;
		Matcher matcher = pattern.matcher(expression);

		if (matcher.matches()) {
			kind = matcher.group(1);
			expr = matcher.group(2);
		} else {
			return expr;
		}

		if ("eval".equals(kind)) {
			return handleGroovy(expr);
		}

		if ("action".equals(kind)) {
			return handleAction(expr);
		}

		if ("call".equals(kind)) {
			return handleCall(expr);
		}

		if ("select".equals(kind)) {
			return handleSelectOne(expr);
		}

		if ("select[]".equals(kind)) {
			return handleSelectAll(expr);
		}

		return expr;
	}

	public Object call(String className, String method) {
		ActionResponse response = new ActionResponse();
		try {
			Class<?> klass = Class.forName(className);
			Method m = klass.getMethod(method,
					ActionRequest.class,
					ActionResponse.class);
			Object obj = injector.getInstance(klass);
			m.invoke(obj, new Object[] { request, response });
		} catch (Exception e) {
			e.printStackTrace();
			response.setException(e);
		}
		return response;
	}

	public Object rpc(String className, String methodCall) {

		Pattern p = Pattern.compile("(\\w+)\\((.*?)\\)");
		Matcher m = p.matcher(methodCall);

		if (!m.matches()) {
			return null;
		}

		try {
			Class<?> klass = Class.forName(className);
			Object object = injector.getInstance(klass);
			return scriptHelper.call(object, methodCall);
		} catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}

	class FormatHelper {

		private final Logger log = LoggerFactory.getLogger(FormatHelper.class);

		public Object escape(Object value) {
			if (value == null) {
				return "";
			}
			return XmlUtil.escapeXml(value.toString());
		}

		public String text(String expr) {
			return getSelectTitle(entity, expr, bindings.get(expr));
		}

		public String text(Object bean, String expr) {
			if (bean == null) {
				return "";
			}
			expr = expr.replaceAll("\\?", "");
			return getSelectTitle(bean.getClass(), expr, getValue(bean, expr));
		}

		private String getSelectTitle(Class<?> klass, String expr, Object value) {
			if (value == null) {
				return "";
			}
			Property property = this.getProperty(klass, expr);
			if (property == null || property.getSelection() == null) {
				return value == null ? "" : value.toString();
			}
			MetaSelectItem item = MetaSelectItem
					.all()
					.filter("self.select.name = ?1 AND self.value = ?2",
							property.getSelection(), value).fetchOne();
			if (item != null) {
				return item.getTitle();
			}
			return value == null ? "" : value.toString();
		}

		private Property getProperty(Class<?> beanClass, String name) {
			Iterator<String> iter = Splitter.on(".").split(name).iterator();
			Property p = Mapper.of(beanClass).getProperty(iter.next());
			while(iter.hasNext() && p != null) {
				p = Mapper.of(p.getTarget()).getProperty(iter.next());
			}
			return p;
		}

		@SuppressWarnings("all")
		private Object getValue(Object bean, String expr) {
			if (bean == null) return null;
			Iterator<String> iter = Splitter.on(".").split(expr).iterator();
			Object obj = null;
			if (bean instanceof Map) {
				obj = ((Map) bean).get(iter.next());
			} else {
				obj = Mapper.of(bean.getClass()).get(bean, iter.next());
			}
			if(iter.hasNext() && obj != null) {
				return getValue(obj, Joiner.on(".").join(iter));
			}
			return obj;
		}

		public void info(String text,  Object... params) {
			log.info(text, params);
		}

		public void debug(String text,  Object... params) {
			log.debug(text, params);
		}

		public void error(String text,  Object... params) {
			log.error(text, params);
		}

		public void trace(String text,  Object... params) {
			log.trace(text, params);
		}

	}

	private String groovyTemplate(String text) {
		if (StringUtils.isBlank(text)) {
			return "";
		}
		text = text.replaceAll("\\$\\{\\s*(\\w+)(\\?)?\\.([^}]*?)\\s*\\|\\s*text\\s*\\}", "\\${__fmt__.text($1, '$3')}");
		text = text.replaceAll("\\$\\{\\s*([^}]*?)\\s*\\|\\s*text\\s*\\}", "\\${__fmt__.text('$1')}");
		text = text.replaceAll("\\$\\{\\s*([^}]*?)\\s*\\|\\s*e\\s*\\}", "\\${($1) ?: ''}");

		if (text.trim().startsWith("<?xml ")) {
			text = text.replaceAll("\\$\\{(.*?)\\}", "\\${__fmt__.escape($1)}");
		}

		bindings.put("__fmt__", new FormatHelper());

		return text;
	}
	
	public String template(Templates engine, Reader template) throws IOException {
		StringBuilder builder = new StringBuilder();
		BufferedReader reader = new BufferedReader(template);
		String line = null;
		while((line = reader.readLine()) != null) {
			builder.append(line).append("\n");
		}
		String text = builder.toString();
		
		if (engine instanceof GroovyTemplates) {
			text = groovyTemplate(text);
		}
		
		return engine.fromText(text).make(bindings).render();
	}

	@SuppressWarnings("all")
	private Query select(String query, Object... params) {
		Preconditions.checkArgument(!Strings.isNullOrEmpty(query));
		if (!query.toLowerCase().startsWith("select "))
			query = "SELECT " + query;

		Query q = JPA.em().createQuery(query);
		QueryBinder.of(q).bind(bindings, params);

		return q;
	}

	public Object selectOne(String query, Object... params) {
		Query q = select(query, params);
		try {
			return q.getSingleResult();
		} catch (NoResultException e) {
		}
		return null;
	}

	public Object selectAll(String query, Object... params) {
		try {
			return select(query, params).getResultList();
		} catch (Exception e) {
		}
		return null;
	}

	@SuppressWarnings("all")
	public Object search(Class<?> entityClass, String filter, Map params) {
		filter = makeMethodCall("filter", filter);
		filter = String.format("%s.all().%s", entityClass.getName(), filter);
		com.axelor.db.Query q = (com.axelor.db.Query) handleGroovy(filter);

		q = q.bind(bindings);
		q = q.bind(params);

		return q.fetchOne();
	}

	private String makeMethodCall(String method, String expression) {
		expression = expression.trim();
		// check if expression is parameterized
		if (!expression.startsWith("(")) {
			if (!expression.matches("('|\")")) {
				expression = "\"\"\"" + expression + "\"\"\"";
			}
			expression = "(" + expression + ")";
		}
		return method + expression;
	}

	private Object handleSelectOne(String expression) {
		expression = makeMethodCall("__me__.selectOne", expression);
		return handleGroovy(expression);
	}

	private Object handleSelectAll(String expression) {
		expression = makeMethodCall("__me__.selectAll", expression);
		return handleGroovy(expression);
	}

	private Object handleGroovy(String expression) {
		return scriptHelper.eval(expression);
	}

	private Object handleAction(String expression) {

		Action action = MetaStore.getAction(expression);
		if (action == null) {
			log.debug("no such action found: {}", expression);
			return null;
		}

		return action.evaluate(this);
	}

	private Object handleCall(String expression) {

		if (Strings.isNullOrEmpty(expression))
			return null;

		String[] parts = expression.split("\\:");
		if (parts.length != 2) {
			log.error("Invalid call expression: ", expression);
			return null;
		}

		ActionMethod action = new ActionMethod();
		ActionMethod.Call call = new ActionMethod.Call();

		call.setController(parts[0]);
		call.setMethod(parts[1]);
		action.setCall(call);

		return action.evaluate(this);
	}

	public ActionResponse execute() {

		ActionResponse response = new ActionResponse();

		String name = request.getAction();
		if (name == null) {
			throw new NullPointerException("no action provided");
		}

		String[] names = name.split(",");
		ActionGroup action = new ActionGroup();
		for(String item : names) {
			action.addAction(item);
		}

		Object data = action.wrap(this);

		if (data instanceof ActionResponse) {
			return (ActionResponse) data;
		}

		response.setData(data);
		response.setStatus(ActionResponse.STATUS_SUCCESS);

		return response;
	}

}
