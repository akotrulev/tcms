/**
 * Clients — the company's own customers, their contacts, and every read that names one
 * (009 module Mc, plan 018).
 *
 * <h2>A client is a record, not a principal — stated first, because it is the decision</h2>
 *
 * <p><strong>No {@code app_user}, no {@code company_membership}, no invitation, no credential and
 * no authentication path</strong>, for a client or for any contact under it (scope D1, confirmed
 * 2026-08-30). Nobody at a client can sign in, see a board, or be granted a permission as a result
 * of anything in this package. {@code CrmCreatesNoLoginTest} asserts both halves: that no write here
 * touches an identity table, and that no schema this module serves carries a credential field.
 *
 * <p>This is what makes the scope-less design safe rather than merely cheap. The only reason a
 * Client would have needed to become a level of the hierarchy is to gate a login, and there is no
 * login to gate. <strong>If a client portal is ever wanted, D1 is reopened rather than
 * extended</strong> — it is a second authentication path and a real confidentiality boundary, and
 * bolting one onto a lookup table is how a tenancy model gets quietly broken.
 *
 * <h2>A client carries no scope</h2>
 *
 * <p>It is not a third gate. The two stay exactly as the roles spec §A1 states them — company
 * filter, then project grant — and a client is visible iff you hold {@code client:read} at company
 * scope, or you can see a project that references it. A scope here would have meant a new
 * {@code RoleAssignment} target, a fourth term in {@code evaluateCompanyWide} and a second place
 * authorization is decided; the rollups this feature wants need none of that.
 *
 * <h2>Not a foundation module, and it does not need to be</h2>
 *
 * <p>The five are {@code api}, {@code tenancy}, {@code identity}, {@code project} and
 * {@code storage}. This module <em>imports</em> {@code project} freely — that direction is allowed —
 * and <strong>nothing imports this one.</strong> Where {@code project} needs a client's name it asks
 * {@link com.tcms.project.ClientDirectory}, which {@link com.tcms.crm.CrmClientDirectory}
 * implements: the declaration sits in the foundation module and the answer comes from here.
 *
 * <p>That is the fourth instance of the pattern in {@code com.tcms.project} —
 * {@code PeopleDirectory} and {@code TeamDirectory} are supplied by {@code identity},
 * {@code TrackerBindingDirectory} by {@code integration}. It is deliberately <em>not</em> the
 * {@code *Facts} pattern: all five of those read native SQL downstream-to-upstream
 * ({@code staffing} → {@code time}, {@code time} → {@code tracker}), and {@code project} → this
 * module is the reverse, which ArchUnit cannot see because SQL is not a Java dependency. A rule that
 * holds only because the checker is blind is one somebody breaks by reading the code.
 *
 * <h2>No money</h2>
 *
 * <p><strong>No rate, no budget, no invoice, no hours figure</strong> — not on a table this module
 * owns and not on a schema it serves. Those are M8's, and scope §7 calls the boundary the sharpest
 * in the expansion: an Account Manager holds {@code client:read} and does not hold {@code billing:*},
 * and {@code client.html} renders the hours-and-invoices table for them with every amount absent.
 * The client page ships that region reserved rather than half-built (018 D-g).
 *
 * <h2>Zero clients is a first-class state</h2>
 *
 * <p>Every read here answers emptily for a company that has never recorded one, and that is a
 * complete answer rather than an unfilled one (scope D1 rule 2). The owner's own worked example is a
 * company building its own products: it never creates a client, every project it owns has a
 * {@code null} {@code client_id}, and <strong>nothing in the product nags, blocks, degrades or
 * marks a project as missing one.</strong>
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>Four of the five foundation modules — {@code storage} is not among them, because nothing here
 * has an attachment: {@code client.html} draws a Contracts panel with signed documents on it, and
 * that panel is built by no module at all (018 D-h). The day it is, this list grows by one entry
 * and the growth is the record that it did.
 *
 * <p>{@code identity} is asked <em>what is this person called</em> and <em>who is in this
 * company</em> — the account manager's name, and whether an {@code accountManagerId} names a
 * member — never <em>may they</em>. The permission question goes through
 * {@code project.ProjectService}, which is the only place in this module that decides anything
 * about access, exactly as in {@code time} and {@code staffing}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project"})
package com.tcms.crm;
