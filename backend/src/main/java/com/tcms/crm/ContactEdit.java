package com.tcms.crm;

/**
 * A contact's fields as the service has validated them.
 *
 * <p><strong>Four fields, and none of them is a credential.</strong> This record is the whole of
 * what a contact write can carry, and it is the shape a reader should check when they want to know
 * whether adding a contact could create an account: there is nothing here to create one with.
 */
record ContactEdit(String name, String role, String email, String phone) {
}
