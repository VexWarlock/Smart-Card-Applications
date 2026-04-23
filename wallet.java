package com.oracle.jcclassic.samples.wallet;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.OwnerPIN;

public class Wallet extends Applet {

    //---- CLA ----
    final static byte WALLET_CLA = (byte) 0x80;

    //---- INS ----
    final static byte VERIFY      = (byte) 0x20;
    final static byte RESET_PIN   = (byte) 0x2C;
    final static byte CREDIT      = (byte) 0x30;
    final static byte DEBIT       = (byte) 0x40;
    final static byte GET_BALANCE = (byte) 0x50;
    final static byte PASS        = (byte) 0x70;

    //---- LIMITE ----
    final static short MAX_BALANCE            = (short) 32767;
    final static byte  MAX_TRANSACTION_AMOUNT = (byte) 127;

    //---- PIN ----
    final static byte PIN_TRY_LIMIT = (byte) 0x03;
    final static byte MAX_PIN_SIZE  = (byte) 0x08;
    final static byte DEFAULT_PIN_SIZE = (byte) 0x04;
    final static byte PUK_SIZE = (byte) 0x08;

    //---- TIPURI ABONAMENT ----
    final static byte SUB_NONE     = (byte) 0x00;
    final static byte SUB_BUS_20   = (byte) 0x01;
    final static byte SUB_TRAM_30  = (byte) 0x02;
    final static byte SUB_DAILY    = (byte) 0x03;

    //---- PRETURI ABONAMENT ----
    final static byte PRICE_BUS_20  = (byte) 60;
    final static byte PRICE_TRAM_30 = (byte) 40;
    final static byte PRICE_DAILY   = (byte) 10;

    //---- TIPURI TRANSPORT ----
    final static byte TRANSPORT_BUS  = (byte) 0x01;
    final static byte TRANSPORT_TRAM = (byte) 0x02;

    //---- PRETURI BILETE ----
    final static byte TICKET_BUS_FULL      = (byte) 4;
    final static byte TICKET_TRAM_FULL     = (byte) 2;
    final static byte TICKET_BUS_REDUCED   = (byte) 3;
    final static byte TICKET_TRAM_REDUCED  = (byte) 1;

    //---- STATUS WORDS ----
    final static short SW_VERIFICATION_FAILED        = (short) 0x6300;
    final static short SW_PIN_VERIFICATION_REQUIRED  = (short) 0x6301;
    final static short SW_INVALID_TRANSACTION_AMOUNT = (short) 0x6A83;
    final static short SW_EXCEED_MAXIMUM_BALANCE     = (short) 0x6A84;
    final static short SW_NEGATIVE_BALANCE           = (short) 0x6A85;
    final static short SW_INVALID_SUBSCRIPTION       = (short) 0x6A86;
    final static short SW_WRONG_TRANSPORT_TYPE       = (short) 0x6A87;
    final static short SW_TOO_MANY_TICKETS           = (short) 0x6A88;
    final static short SW_PIN_BLOCKED                = (short) 0x6982;
    final static short SW_INVALID_DAY_OR_HOUR        = (short) 0x6A89;
    final static short SW_INVALID_PIN_LENGTH         = (short) 0x6A80;

    //---- STARE ----
    private OwnerPIN pin;
    private short balance;

    private byte  subType;
    private short subTripsLeft;
    private byte  subDay; //doar ziua sapt,nu data

    private Wallet(byte[] bArray, short bOffset, byte bLength) { //constructorul
        pin = new OwnerPIN(PIN_TRY_LIMIT, MAX_PIN_SIZE); //obiectul pin

        byte iLen = bArray[bOffset];
        bOffset = (short) (bOffset + iLen + 1);
        byte cLen = bArray[bOffset];
        bOffset = (short) (bOffset + cLen + 1);

        byte[] defaultPin = { (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04 };  //pin default
        pin.update(defaultPin, (short) 0, DEFAULT_PIN_SIZE);

        balance = 0;
        subType = SUB_NONE;
        subTripsLeft = 0;
        subDay = 0;

        register();
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new Wallet(bArray, bOffset, bLength);
    }

    public boolean select() {
        return pin.getTriesRemaining() != 0;
    }

    public void deselect() {
        pin.reset();
    }

    public void process(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        if (apdu.isISOInterindustryCLA()) { //verifica daca CLA standard ISO
            if (buffer[ISO7816.OFFSET_INS] == (byte) 0xA4) { //ins=select => o accepta
                return;
            }
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);//altfel,arunca eroare
        }

        if (buffer[ISO7816.OFFSET_CLA] != WALLET_CLA) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);//CLA!=0x80 => comanda respinsa
        }

        switch (buffer[ISO7816.OFFSET_INS]) { //alege ce metoda sa execute dupa INS
            case VERIFY:
                verify(apdu);
                return;
            case RESET_PIN:
                resetPin(apdu);
                return;
            case CREDIT:
                credit(apdu);
                return;
            case DEBIT:
                debit(apdu);
                return;
            case GET_BALANCE:
                getBalance(apdu);
                return;
            case PASS:
                pass(apdu);
                return;
            default:
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED); //exceptie daca nu e recunoscut
        }
    }

    private void verify(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        if (pin.getTriesRemaining() == 0) { //daca nu mai is incercari,pinul e blocat
            ISOException.throwIt(SW_PIN_BLOCKED);
        }

        byte byteRead = (byte) apdu.setIncomingAndReceive();//applet ul citeste datele

        if (!pin.check(buffer, ISO7816.OFFSET_CDATA, byteRead)) { //verifica daca pinul coincide
            ISOException.throwIt(SW_VERIFICATION_FAILED);
        }
    }

    private void credit(APDU apdu) {
        if (!pin.isValidated()) {//verifica ca pinul sa fie validat
            ISOException.throwIt(SW_PIN_VERIFICATION_REQUIRED);
        }

        byte[] buffer = apdu.getBuffer(); //citeste datele
        byte numBytes = buffer[ISO7816.OFFSET_LC];
        byte byteRead = (byte) apdu.setIncomingAndReceive();

        if ((numBytes != 1) || (byteRead != 1)) { //CREDIT tre sa primeasca fix un byte,adica suma
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        byte creditAmount = buffer[ISO7816.OFFSET_CDATA];

        if ((creditAmount < 0) || (creditAmount > MAX_TRANSACTION_AMOUNT)) { //verifica suma sa fie valida
            ISOException.throwIt(SW_INVALID_TRANSACTION_AMOUNT);
        }

        if ((short) (balance + creditAmount) > MAX_BALANCE) {//nu permite depasire sold maxim
            ISOException.throwIt(SW_EXCEED_MAXIMUM_BALANCE);
        }

        balance = (short) (balance + creditAmount);
    }

    private void pass(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        byte subTypeReq = buffer[ISO7816.OFFSET_P1]; //p1=tip abonament
        byte currentDay = buffer[ISO7816.OFFSET_P2]; //p2=ziua curenta

        if (currentDay < 1 || currentDay > 7) { //verificare zi valida
            ISOException.throwIt(SW_INVALID_DAY_OR_HOUR);
        }

        byte price;
        short trips;

        switch (subTypeReq) { //tipuri abonamente
            case SUB_BUS_20:
                price = PRICE_BUS_20;
                trips = 20;
                break;
            case SUB_TRAM_30:
                price = PRICE_TRAM_30;
                trips = 30;
                break;
            case SUB_DAILY:
                price = PRICE_DAILY;
                trips = (short) -1; //calatorii nelimitate
                break;
            default:
                ISOException.throwIt(SW_INVALID_SUBSCRIPTION); //altfel,invalid
                return;
        }

        if ((short) (balance - price) < 0) { //eroare bani insuficienti
            ISOException.throwIt(SW_NEGATIVE_BALANCE);
        }

        balance = (short) (balance - price);
        subType = subTypeReq;
        subTripsLeft = trips;
        subDay = (subTypeReq == SUB_DAILY) ? currentDay : (byte) 0;
    }

    private void debit(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        byte transport = buffer[ISO7816.OFFSET_P1]; //p1=tip transport
        byte persons   = buffer[ISO7816.OFFSET_P2]; //p2=nr persoane

        byte numBytes = buffer[ISO7816.OFFSET_LC];
        byte byteRead = (byte) apdu.setIncomingAndReceive(); //citeste datele

        if ((numBytes != 2) || (byteRead != 2)) { //asteapta 2 biti la data(zi si ora)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        byte currentDay = buffer[ISO7816.OFFSET_CDATA];
        byte currentHour = buffer[ISO7816.OFFSET_CDATA + 1];

        if ((transport != TRANSPORT_BUS) && (transport != TRANSPORT_TRAM)) { //bus sau tram
            ISOException.throwIt(SW_WRONG_TRANSPORT_TYPE);
        }

        if ((persons < 1) || (persons > 20)) { //intre 1 si 20 persoane
            ISOException.throwIt(SW_TOO_MANY_TICKETS);
        }

        if ((currentDay < 1) || (currentDay > 7) || (currentHour < 0) || (currentHour > 23)) {
            ISOException.throwIt(SW_INVALID_DAY_OR_HOUR); //validare zi,ora
        }

        if (persons == 1) { //cazul cu o persoana
            if (subType == SUB_DAILY && subTripsLeft == (short) -1 && subDay == currentDay) {
                return; //daca e deja abonament pe ziua curenta,nu scade nimic
            }

            if (hasCompatibleSubscription(transport)) {
                subTripsLeft = (short) (subTripsLeft - 1); //daca exista abonament activ,scare o calatorie
                if (subTripsLeft == 0) { //expira daca nu mai are calatorii
                    subType = SUB_NONE;
                }
                return;
            }

            short price = getTicketPrice(transport, currentDay, currentHour); //atlfel,bilet normal
            if ((short) (balance - price) < 0) { //verifica daca ai bani
                ISOException.throwIt(SW_NEGATIVE_BALANCE);
            }
            balance = (short) (balance - price);
            return;
        }

        short totalCost = 0;

        if (subType == SUB_DAILY && subTripsLeft == (short) -1 && subDay == currentDay) {
            // titularul merge gratis
        } else if (hasCompatibleSubscription(transport)) {
            subTripsLeft = (short) (subTripsLeft - 1);
            if (subTripsLeft == 0) {
                subType = SUB_NONE; //abonament combatibi => scade o calatorie
            }
        } else { //daca nu are abonament,plateste un bilet si el
            totalCost = (short) (totalCost + getTicketPrice(transport, currentDay, currentHour));
        }

        short ticketPrice = getTicketPrice(transport, currentDay, currentHour);
        short extraPersons = (short) (persons - 1); //nr pers suplimentare
        totalCost = (short) (totalCost + (short) (extraPersons * ticketPrice)); //adauga costul biletelor pt ceilalti

        if (persons > 10) { // mai multi de 10 au reducere de 20%
            totalCost = (short) ((short) (totalCost * 80) / 100);
        }

        if ((short) (balance - totalCost) < 0) { //verifica daca sunt bani
            ISOException.throwIt(SW_NEGATIVE_BALANCE);
        }

        balance = (short) (balance - totalCost);
    }

    private boolean hasCompatibleSubscription(byte transport) {
        if (subType == SUB_NONE) return false; //nu exista abonament
        if ((subTripsLeft <= 0) && (subTripsLeft != (short) -1)) return false; //nu mai sunt calatorii

		//verifica perechile compatibile
        if (transport == TRANSPORT_BUS && subType == SUB_BUS_20) return true;
        if (transport == TRANSPORT_TRAM && subType == SUB_TRAM_30) return true;

        return false;
    }

	//calculeaza pretul in fct de tip transport,zi,ora
    private short getTicketPrice(byte transport, byte day, byte hour) {
        boolean isWeekend = (day == 6 || day == 7);
        boolean isMorningWeekday = (!isWeekend && hour >= 10 && hour < 12);

        if (isWeekend) { //reducere de weekend 50%
            if (transport == TRANSPORT_BUS) return (short) (TICKET_BUS_FULL / 2);
            return (short) (TICKET_TRAM_FULL / 2);
        }

        if (isMorningWeekday) { //reducere curs saptamana interval 10-12
            if (transport == TRANSPORT_BUS) return (short) TICKET_BUS_REDUCED;
            return (short) TICKET_TRAM_REDUCED;
        }

        if (transport == TRANSPORT_BUS) return (short) TICKET_BUS_FULL; //caz normal
        return (short) TICKET_TRAM_FULL;
    }




    private void getBalance(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        short le = apdu.setOutgoing(); //citeste cati biti poate primi terminalul

        if (le < 4) { //macar 4 biti
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        apdu.setOutgoingLength((byte) 4); //spune ca trimite exact 4 biti

		//punem soldul pe 2 bytes
        buffer[0] = (byte) (balance >> 8);
        buffer[1] = (byte) (balance & 0xFF);
        buffer[2] = subType; //byte-ul 3 e tipul abonamentului

        if (subType == SUB_DAILY && subTripsLeft == (short) -1) {
            buffer[3] = (byte) 0xFF; //ultimul byte FF daca e daily
        } else {
            buffer[3] = (byte) (subTripsLeft & 0xFF); //altfel,nr de calatorii
        }

        apdu.sendBytes((short) 0, (short) 4); //trimite cei 4 biti la terminal
    }




    private void resetPin(APDU apdu) {
        byte[] buffer = apdu.getBuffer();
        byte byteRead = (byte) apdu.setIncomingAndReceive(); //citeste datele primite

        if (byteRead != (byte) 12) { //tre sa fie exact 12 bytes(8 pt PUK,4 pt PIN)
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        if (pin.getTriesRemaining() != 0) { //resetare doar daca pinul e deja blocat
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        short base = ISO7816.OFFSET_CDATA;

        for (byte i = 0; i < PUK_SIZE; i++) {
            if (buffer[(short) (base + i)] != (byte) 0x09) { //verif daca bitii din PUK sunt 09
                ISOException.throwIt(SW_VERIFICATION_FAILED);
            }
        }

        byte newPinLength = DEFAULT_PIN_SIZE;
        if (newPinLength < 1 || newPinLength > MAX_PIN_SIZE) { //vede daca are lungimea potrivita
            ISOException.throwIt(SW_INVALID_PIN_LENGTH);
        }

        pin.update(buffer, (short) (base + PUK_SIZE), newPinLength);
        pin.resetAndUnblock(); //deblocheaza pinul,reseteaza contorul de incercari
    }
}
