/**
 * Copyright (c) 2012-2014 Axelor. All Rights Reserved.
 *
 * The contents of this file are subject to the Common Public
 * Attribution License Version 1.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://license.axelor.com/.
 *
 * The License is based on the Mozilla Public License Version 1.1 but
 * Sections 14 and 15 have been added to cover use of software over a
 * computer network and provide for limited attribution for the
 * Original Developer. In addition, Exhibit A has been modified to be
 * consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is part of "Axelor Business Suite", developed by
 * Axelor exclusively.
 *
 * The Original Developer is the Initial Developer. The Initial Developer of
 * the Original Code is Axelor.
 *
 * All portions of the code written by Axelor are
 * Copyright (c) 2012-2014 Axelor. All Rights Reserved.
 */
package com.axelor.meta.web;

import javax.inject.Inject;

import com.axelor.meta.loader.ModuleManager;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.Response;
import com.google.inject.persist.Transactional;

public class ModuleController {
	
	@Inject
	private ModuleManager loader;

	@Transactional
	public Response install(final String name) {
		final ActionResponse response = new ActionResponse();
		try {
			loader.install(name, false, false);
			response.setReload(true);
		} catch (Exception e) {
			response.setException(e);
		}
		return response;
	}

	@Transactional
	public Response uninstall(final String name) {
		final ActionResponse response = new ActionResponse();
		try {
			loader.uninstall(name);
			response.setReload(true);
		} catch (Exception e) {
			response.setException(e);
		}
		return response;
	}
}
