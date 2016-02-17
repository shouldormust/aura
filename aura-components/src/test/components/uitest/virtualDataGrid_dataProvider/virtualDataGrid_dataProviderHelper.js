/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
({
    provide: function(component, event, controller) {
        var sortBy = component.get("v.sortBy");
        var pageSize = component.get("v.pageSize");
    	var data = [
           {
               id: "0",
               name: "Mary Scott Fitzgerald",
               gender: "F",
               age: "7",
               grade: "2nd"
           }, {
               id: "1",
               name: "Dan Neal",
               gender: "M",
               age: "8",
               grade: "3rd"
           }, {
               id: "2",
               name: "Helen Beagle",
               gender: "F",
               age: "9",
               grade: "4th"
           }, {
               id: "3",
               name: "Monica Rodrige",
               gender: "F",
               age: "10",
               grade: "5th"
           }, {
               id: "4",
               name: "Jenny Wang",
               gender: "F",
               age: "7",
               grade: "1st"
           }, {
               id: "5",
               name: "Michael Hass",
               gender: "M",
               age: "10",
               grade: "4th"
           }, {
               id: "6",
               name: "Katty Liu",
               gender: "F",
               age: "10",
               grade: "5th"
           }, {
               id: "7",
               name: "Preeya Khan",
               gender: "F",
               age: "9",
               grade: "4th"
           }, {
               id: "8",
               name: "Ravi Vaz",
               gender: "M",
               age: "8",
               grade: "3rd"
           }, {
               id: "9",
               name: "Ted Snider",
               gender: "M",
               age: "6",
               grade: "1st"
           }, {
               id: "10",
               name: "Carl Swasson",
               gender: "M",
               age: "9",
               grade: "4th"
           }
       ];
    	var helper = this;
    	data.sort(helper.compare(sortBy));
        var dataProvider = component.getConcreteComponent();
        this.fireDataChangeEvent(dataProvider, data);
    },
    
    compare: function(sortBy) {
    	if (sortBy.indexOf("id") >=0 || sortBy.indexOf("age") >= 0) {
    		return function(item1, item2) {
        		if (sortBy.indexOf("-") == 0) {
        			return -1 * (item1[sortBy.substring(1)] - item2[sortBy.substring(1)]);
        		} else {
        			return item1[sortBy] - item2[sortBy]; 
        		}
        	}
    	} else {
    	    return function(item1, item2) {
    		    if (sortBy.indexOf("-") == 0) {
    			    return -1 * (item1[sortBy.substring(1)].localeCompare(item2[sortBy.substring(1)]));
    		    } else {
    			    return item1[sortBy].localeCompare(item2[sortBy]); 
    		    }
    	    }
    	}
    }
})