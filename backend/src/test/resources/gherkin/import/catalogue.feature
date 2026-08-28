Feature: Catalogue

  Scenario: Browse the catalogue
    Given the shop is open
    When I open the catalogue
    Then I see the products
